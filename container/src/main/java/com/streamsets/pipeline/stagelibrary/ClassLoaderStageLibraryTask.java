/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stagelibrary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.impl.LocaleInContext;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.ConfigDefinition;
import com.streamsets.pipeline.config.ErrorHandlingChooserValues;
import com.streamsets.pipeline.config.PipelineDefinition;
import com.streamsets.pipeline.config.StageDefinition;
import com.streamsets.pipeline.config.StageLibraryDefinition;
import com.streamsets.pipeline.definition.StageDefinitionExtractor;
import com.streamsets.pipeline.definition.StageLibraryDefinitionExtractor;
import com.streamsets.pipeline.el.RuntimeEL;
import com.streamsets.pipeline.json.ObjectMapperFactory;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.task.AbstractTask;

import com.streamsets.pipeline.util.Configuration;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class ClassLoaderStageLibraryTask extends AbstractTask implements StageLibraryTask {
  public static final String MAX_PRIVATE_STAGE_CLASS_LOADERS_KEY = "max.stage.private.classloaders";
  public static final int MAX_PRIVATE_STAGE_CLASS_LOADERS_DEFAULT = 50;

  private static final Logger LOG = LoggerFactory.getLogger(ClassLoaderStageLibraryTask.class);

  private final RuntimeInfo runtimeInfo;
  private final Configuration configuration;
  private List<? extends ClassLoader> stageClassLoaders;
  private Map<String, StageDefinition> stageMap;
  private List<StageDefinition> stageList;
  private LoadingCache<Locale, List<StageDefinition>> localizedStageList;
  private ObjectMapper json;
  private KeyedObjectPool<String, ClassLoader> privateClassLoaderPool;

  @Inject
  public ClassLoaderStageLibraryTask(RuntimeInfo runtimeInfo, Configuration configuration) {
    super("stageLibrary");
    this.runtimeInfo = runtimeInfo;
    this.configuration = configuration;
  }

  private Method duplicateClassLoaderMethod;
  private Method getClassLoaderKeyMethod;
  private Method isPrivateClassLoaderMethod;

  private void resolveClassLoaderMethods(ClassLoader cl) {
    if (cl.getClass().getSimpleName().equals("SDCClassLoader")) {
      try {
        duplicateClassLoaderMethod = cl.getClass().getMethod("duplicateStageClassLoader");
        getClassLoaderKeyMethod = cl.getClass().getMethod("getName");
        isPrivateClassLoaderMethod = cl.getClass().getMethod("isPrivate");
      } catch (Exception ex) {
        throw new Error(ex);
      }
    } else {
      LOG.warn("No SDCClassLoaders available, there is no class isolation");
    }
  }

  @SuppressWarnings("unchecked")
  private <T> T invoke(Method method, ClassLoader cl, Class<T> returnType) {
    try {
      return (T) method.invoke(cl);
    } catch (Exception ex) {
      throw new Error(ex);
    }
  }

  private ClassLoader duplicateClassLoader(ClassLoader cl) {
    return (duplicateClassLoaderMethod == null) ? cl : invoke(duplicateClassLoaderMethod, cl, ClassLoader.class);
  }

  private String getClassLoaderKey(ClassLoader cl) {
    return (getClassLoaderKeyMethod == null) ? "key" : invoke(getClassLoaderKeyMethod, cl, String.class);
  }

  private boolean isPrivateClassLoader(ClassLoader cl) {
    if (cl != getClass().getClassLoader()) { // if we are the container CL we are not private for sure
      return (isPrivateClassLoaderMethod == null) ? false : invoke(isPrivateClassLoaderMethod, cl, Boolean.class);
    } else {
      return  false;
    }
  }

  private class ClassLoaderFactory extends BaseKeyedPooledObjectFactory<String, ClassLoader> {
    private final Map<String, ClassLoader> classLoaderMap;

    public ClassLoaderFactory(List<? extends ClassLoader> classLoaders) {
      classLoaderMap = new HashMap<>();
      for (ClassLoader cl : classLoaders) {
        classLoaderMap.put(getClassLoaderKey(cl), cl);
      }
    }

    @Override
    public ClassLoader create(String key) throws Exception {
      return duplicateClassLoader(classLoaderMap.get(key));
    }

    @Override
    public PooledObject<ClassLoader> wrap(ClassLoader value) {
      return new DefaultPooledObject<>(value);
    }
  }

  @Override
  public void initTask() {
    super.initTask();
    stageClassLoaders = runtimeInfo.getStageLibraryClassLoaders();
    if (!stageClassLoaders.isEmpty()) {
      resolveClassLoaderMethods(stageClassLoaders.get(0));
    }
    json = ObjectMapperFactory.get();
    stageList = new ArrayList<>();
    stageMap = new HashMap<>();
    loadStages();
    stageList = ImmutableList.copyOf(stageList);
    stageMap = ImmutableMap.copyOf(stageMap);

    // localization cache for definitions
    localizedStageList = CacheBuilder.newBuilder().build(new CacheLoader<Locale, List<StageDefinition>>() {
      @Override
      public List<StageDefinition> load(Locale key) throws Exception {
        List<StageDefinition> list = new ArrayList<>();
        for (StageDefinition stage : stageList) {
          list.add(stage.localize());
        }
        return list;
      }
    });

    // initializing the list of targets that can be used for error handling
    ErrorHandlingChooserValues.setErrorHandlingOptions(this);

    // initializing the pool of private stage classloaders
    GenericKeyedObjectPoolConfig poolConfig = new GenericKeyedObjectPoolConfig();
    poolConfig.setJmxEnabled(false);
    poolConfig.setMaxTotal(configuration.get(MAX_PRIVATE_STAGE_CLASS_LOADERS_KEY,
                                             MAX_PRIVATE_STAGE_CLASS_LOADERS_DEFAULT));
    poolConfig.setMinEvictableIdleTimeMillis(-1);
    poolConfig.setNumTestsPerEvictionRun(0);
    poolConfig.setMaxIdlePerKey(-1);
    poolConfig.setMinIdlePerKey(0);
    poolConfig.setMaxTotalPerKey(-1);
    poolConfig.setBlockWhenExhausted(false);
    poolConfig.setMaxWaitMillis(0);
    privateClassLoaderPool = new GenericKeyedObjectPool<>(new ClassLoaderFactory(stageClassLoaders), poolConfig);
  }

  @Override
  protected void stopTask() {
    privateClassLoaderPool.close();
    super.stopTask();
  }

  @VisibleForTesting
  void loadStages() {
    if (LOG.isDebugEnabled()) {
      for (ClassLoader cl : stageClassLoaders) {
        LOG.debug("About to load stages from library '{}'", StageLibraryUtils.getLibraryName(cl));
      }
    }

    try {
      RuntimeEL.loadRuntimeConfiguration(runtimeInfo);
    } catch (IOException e) {
      throw new RuntimeException(
        Utils.format("Could not load runtime configuration, '{}'", e.getMessage()), e);
    }

    try {
      int libs = 0;
      int stages = 0;
      long start = System.currentTimeMillis();
      LocaleInContext.set(Locale.getDefault());
      for (ClassLoader cl : stageClassLoaders) {
        libs++;
        StageLibraryDefinition libDef = StageLibraryDefinitionExtractor.get().extract(cl);
        LOG.debug("Loading stages from library '{}'", libDef.getName());
        try {
          Enumeration<URL> resources = cl.getResources(STAGES_DEFINITION_RESOURCE);
          while (resources.hasMoreElements()) {
            Map<String, String> stagesInLibrary = new HashMap<>();
            URL url = resources.nextElement();
            try (InputStream is = url.openStream()) {
              Map<String, List<String>> libraryInfo = json.readValue(is, Map.class);
              for (String className : libraryInfo.get("stageClasses")) {
                stages++;
                Class<? extends Stage> klass = (Class<? extends Stage>) cl.loadClass(className);
                StageDefinition stage = StageDefinitionExtractor.get().
                    extract(libDef, klass, Utils.formatL("Library='{}'", libDef.getName()));
                String key = createKey(libDef.getName(), stage.getName(), stage.getVersion());
                LOG.debug("Loaded stage '{}' (library:name:version)", key);
                if (stagesInLibrary.containsKey(key)) {
                  throw new IllegalStateException(Utils.format(
                      "Library '{}' contains more than one definition for stage '{}', class '{}' and class '{}'",
                      libDef.getName(), key, stagesInLibrary.get(key), stage.getStageClass()));
                }
                stagesInLibrary.put(key, stage.getClassName());
                stageList.add(stage);
                stageMap.put(key, stage);
                computeDependsOnChain(stage);
              }
            }
          }
        } catch (IOException | ClassNotFoundException ex) {
          throw new RuntimeException(
              Utils.format("Could not load stages definition from '{}', {}", cl, ex.getMessage()), ex);
        }
      }
      LOG.debug("Loaded '{}' libraries with a total of '{}' stages in '{}ms'", libs, stages,
                System.currentTimeMillis() - start);
    } finally {
      LocaleInContext.set(null);
    }
  }

  private void computeDependsOnChain(StageDefinition stageDefinition) {
    Map<String, ConfigDefinition> configDefinitionsMap = stageDefinition.getConfigDefinitionsMap();
    for(Map.Entry<String, ConfigDefinition> entry :  configDefinitionsMap.entrySet()) {
      ConfigDefinition configDef = entry.getValue();
      ConfigDefinition tempConfigDef = configDef;
      Map<String, List<Object>> dependsOnMap = new HashMap<>();
      while(tempConfigDef != null &&
        tempConfigDef.getDependsOn() != null &&
        !tempConfigDef.getDependsOn().isEmpty()) {

        dependsOnMap.put(tempConfigDef.getDependsOn(), tempConfigDef.getTriggeredByValues());
        tempConfigDef = configDefinitionsMap.get(tempConfigDef.getDependsOn());
      }
      if(dependsOnMap.isEmpty()) {
        //Request from UI to set null for efficiency
        configDef.setDependsOnMap(null);
      } else {
        configDef.setDependsOnMap(dependsOnMap);
      }
    }
  }

  private String createKey(String library, String name, String version) {
    return library + ":" + name + ":" + version;
  }

  @Override
  public PipelineDefinition getPipeline() {
    return PipelineDefinition.getPipelineDef();
  }

  @Override
  public List<StageDefinition> getStages() {
    try {
      return (LocaleInContext.get() == null) ? stageList : localizedStageList.get(LocaleInContext.get());
    } catch (ExecutionException ex) {
      LOG.warn("Error loading locale '{}', {}", LocaleInContext.get(), ex.getMessage(), ex);
      return stageList;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public StageDefinition getStage(String library, String name, String version, boolean forExecution) {
    StageDefinition def = stageMap.get(createKey(library, name, version));
    if (forExecution &&  def.isPrivateClassLoader()) {
      def = new StageDefinition(def, getStageClassLoader(def));
    }
    return def;
  }

  ClassLoader getStageClassLoader(StageDefinition stageDefinition) {
    ClassLoader cl = stageDefinition.getStageClassLoader();
    if (stageDefinition.isPrivateClassLoader()) {
      String key = getClassLoaderKey(cl);
      try {
        cl = privateClassLoaderPool.borrowObject(key);
        LOG.debug("Got a private ClassLoader for '{}', for stage '{}', active private ClassLoaders='{}'",
                  key, stageDefinition.getName(), privateClassLoaderPool.getNumActive());
      } catch (Exception ex) {
        LOG.warn("Could not get a private ClassLoader for '{}', for stage '{}', active private ClassLoaders='{}'",
                 key, stageDefinition.getName(), privateClassLoaderPool.getNumActive());
        throw new RuntimeException(ex);
      }
    }
    return cl;
  }

  @Override
  public void releaseStageClassLoader(ClassLoader classLoader) {
    if (isPrivateClassLoader(classLoader)) {
      String key = getClassLoaderKey(classLoader);
      try {
        LOG.debug("Returning private ClassLoader for '{}'", key);
        privateClassLoaderPool.returnObject(key, classLoader);
        LOG.debug("Returned a private ClassLoader for '{}', active private ClassLoaders='{}'",
                  key, privateClassLoaderPool.getNumActive());
      } catch (Exception ex) {
        LOG.warn("Could not return a private ClassLoader for '{}', active private ClassLoaders='{}'",
                 key, privateClassLoaderPool.getNumActive());
        throw new RuntimeException(ex);
      }
    }
  }

}
