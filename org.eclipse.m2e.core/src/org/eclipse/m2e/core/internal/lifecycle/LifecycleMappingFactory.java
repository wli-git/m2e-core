/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.core.internal.lifecycle;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;

import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.core.IMavenConstants;
import org.eclipse.m2e.core.core.MavenLogger;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.internal.lifecycle.model.LifecycleMappingMetadata;
import org.eclipse.m2e.core.internal.lifecycle.model.LifecycleMappingMetadataSource;
import org.eclipse.m2e.core.internal.lifecycle.model.PluginExecutionAction;
import org.eclipse.m2e.core.internal.lifecycle.model.PluginExecutionFilter;
import org.eclipse.m2e.core.internal.lifecycle.model.PluginExecutionMetadata;
import org.eclipse.m2e.core.internal.lifecycle.model.io.xpp3.LifecycleMappingMetadataSourceXpp3Reader;
import org.eclipse.m2e.core.internal.project.IgnoreMojoProjectConfiguration;
import org.eclipse.m2e.core.internal.project.MojoExecutionProjectConfigurator;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.configurator.AbstractLifecycleMapping;
import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;
import org.eclipse.m2e.core.project.configurator.CustomizableLifecycleMapping;
import org.eclipse.m2e.core.project.configurator.ILifecycleMapping;
import org.eclipse.m2e.core.project.configurator.LifecycleMappingConfigurationException;


/**
 * LifecycleMappingFactory
 * 
 * @author igor
 */
public class LifecycleMappingFactory {

  public static final String EXTENSION_LIFECYCLE_MAPPINGS = IMavenConstants.PLUGIN_ID + ".lifecycleMappings"; //$NON-NLS-1$

  public static final String EXTENSION_PROJECT_CONFIGURATORS = IMavenConstants.PLUGIN_ID + ".projectConfigurators"; //$NON-NLS-1$

  private static final String ELEMENT_LIFECYCLE_MAPPING = "lifecycleMapping"; //$NON-NLS-1$

  private static final String ATTR_CLASS = "class"; //$NON-NLS-1$

  private static final String ATTR_PACKAGING_TYPE = "packaging-type"; //$NON-NLS-1$

  private static final String ATTR_ID = "id"; //$NON-NLS-1$

  private static final String ATTR_NAME = "name"; //$NON-NLS-1$

  private static final String ELEMENT_CONFIGURATOR = "configurator"; //$NON-NLS-1$

  private static final String ELEMENT_PLUGIN_EXECUTION = "pluginExecution"; //$NON-NLS-1$

  private static final String ELEMENT_PLUGIN_EXECUTION_FILTER = "pluginExecutionFilter"; //$NON-NLS-1$

  private static final String ELEMENT_RUN_ON_INCREMENTAL = "runOnIncremental";

  private static final String ATTR_GROUPID = "groupId";

  private static final String ATTR_ARTIFACTID = "artifactId";

  private static final String ATTR_VERSION = "version";

  private static final String LIFECYCLE_MAPPING_METADATA_CLASSIFIER = "lifecycle-mapping-metadata";

  public static ILifecycleMapping getLifecycleMapping(IMavenProjectFacade mavenProjectFacade, String packagingType) {
    for(LifecycleMappingMetadataSource lifecycleMappingMetadataSource : mavenProjectFacade
        .getLifecycleMappingMetadataSources()) {
      for(LifecycleMappingMetadata lifecycleMappingMetadata : lifecycleMappingMetadataSource.getLifecycleMappings()) {
        if(packagingType.equals(lifecycleMappingMetadata.getPackagingType())) {
          return getLifecycleMapping(lifecycleMappingMetadata.getLifecycleMappingId());
        }
      }
    }
    return getLifecycleMappingForPackagingType(packagingType);
  }

  /**
   * Returns default lifecycle mapping for specified packaging type or null if no such lifecycle mapping
   */
  private static ILifecycleMapping getLifecycleMappingForPackagingType(String packagingType) {
    IExtensionRegistry registry = Platform.getExtensionRegistry();
    IExtensionPoint configuratorsExtensionPoint = registry.getExtensionPoint(EXTENSION_LIFECYCLE_MAPPINGS);
    if(configuratorsExtensionPoint != null) {
      IExtension[] configuratorExtensions = configuratorsExtensionPoint.getExtensions();
      for(IExtension extension : configuratorExtensions) {
        IConfigurationElement[] elements = extension.getConfigurationElements();
        for(IConfigurationElement element : elements) {
          if(element.getName().equals(ELEMENT_LIFECYCLE_MAPPING)) {
            if(packagingType.equals(element.getAttribute(ATTR_PACKAGING_TYPE))) {
              return createLifecycleMapping(element);
            }
          }
        }
      }
    }
    return null;
  }

  private static AbstractProjectConfigurator createProjectConfigurator(PluginExecutionMetadata pluginExecutionMetadata) {
    PluginExecutionAction pluginExecutionAction = pluginExecutionMetadata.getAction();
    if(pluginExecutionAction == PluginExecutionAction.IGNORE) {
      return new IgnoreMojoProjectConfiguration(pluginExecutionMetadata.getFilter());
    }
    if(pluginExecutionAction == PluginExecutionAction.EXECUTE) {
      return createMojoExecution(pluginExecutionMetadata);
    }
    if(pluginExecutionAction == PluginExecutionAction.CONFIGURATOR) {
      Xpp3Dom child = pluginExecutionMetadata.getConfiguration().getChild(ATTR_ID);
      if(child == null || child.getValue().trim().length() == 0) {
        throw new LifecycleMappingConfigurationException("A configurator id must be specified");
      }
      String configuratorId = child.getValue();
      AbstractProjectConfigurator projectConfigurator = createProjectConfigurator(configuratorId, true/*bare*/);
      if(projectConfigurator == null) {
        String message = "Project configurator '"
            + configuratorId
            + "' is not available. To enable full functionality, install the project configurator and run Maven->Update Project Configuration.";
        throw new LifecycleMappingConfigurationException(message);
      }
      projectConfigurator.addPluginExecutionFilter(pluginExecutionMetadata.getFilter());
      return projectConfigurator;
    }
    throw new IllegalStateException("An action must be specified.");
  }

  private static ILifecycleMapping createLifecycleMapping(IConfigurationElement element) {
    String mappingId = null;
    try {
      ILifecycleMapping mapping = (ILifecycleMapping) element.createExecutableExtension(ATTR_CLASS);
      mappingId = element.getAttribute(ATTR_ID);
      if(mapping instanceof AbstractLifecycleMapping) {
        AbstractLifecycleMapping abstractLifecycleMapping = (AbstractLifecycleMapping) mapping;
        abstractLifecycleMapping.setId(mappingId);
        abstractLifecycleMapping.setName(element.getAttribute(ATTR_NAME));
      }
      if(mapping instanceof CustomizableLifecycleMapping) {
        CustomizableLifecycleMapping customizable = (CustomizableLifecycleMapping) mapping;
        for(IConfigurationElement pluginExecution : element.getChildren(ELEMENT_PLUGIN_EXECUTION)) {
          String pluginExecutionXml = toXml(pluginExecution);
          AbstractProjectConfigurator configurator = createProjectConfigurator(pluginExecutionXml);
          customizable.addConfigurator(configurator);
        }
      }
      return mapping;
    } catch(CoreException ex) {
      MavenLogger.log(ex);
    } catch(IOException e) {
      throw new LifecycleMappingConfigurationException("Cannot read lifecycle mapping metadata for " + mappingId, e);
    } catch(XmlPullParserException e) {
      throw new LifecycleMappingConfigurationException("Cannot parse lifecycle mapping metadata for " + mappingId, e);
    }
    return null;
  }

  public static AbstractProjectConfigurator createProjectConfigurator(String pluginExecutionXml) throws IOException,
      XmlPullParserException {
    PluginExecutionMetadata pluginExecutionMetadata = new LifecycleMappingMetadataSourceXpp3Reader()
        .readPluginExecutionMetadata(new StringReader(pluginExecutionXml));
    AbstractProjectConfigurator configurator = createProjectConfigurator(pluginExecutionMetadata);
    configurator.addPluginExecutionFilter(pluginExecutionMetadata.getFilter());
    return configurator;
  }

  private static AbstractProjectConfigurator createMojoExecution(PluginExecutionMetadata pluginExecutionMetadata) {
    boolean runOnIncremental = true;
    Xpp3Dom child = pluginExecutionMetadata.getConfiguration().getChild(ELEMENT_RUN_ON_INCREMENTAL);
    if(child != null) {
      runOnIncremental = Boolean.parseBoolean(child.getValue());
    }
    return new MojoExecutionProjectConfigurator(pluginExecutionMetadata.getFilter(), runOnIncremental);
  }

  private static PluginExecutionFilter createPluginExecutionFilter(IConfigurationElement configurationElement) {
    String configurationElementXml = toXml(configurationElement);
    try {
      return new LifecycleMappingMetadataSourceXpp3Reader().readPluginExecutionFilter(new StringReader(
          configurationElementXml));
    } catch(IOException e) {
      throw new LifecycleMappingConfigurationException("Cannot read plugin execution filter", e);
    } catch(XmlPullParserException e) {
      throw new LifecycleMappingConfigurationException("Cannot parse plugin execution filter", e);
    }
  }

  public static ILifecycleMapping getLifecycleMapping(String mappingId) {
    IExtensionRegistry registry = Platform.getExtensionRegistry();
    IExtensionPoint configuratorsExtensionPoint = registry.getExtensionPoint(EXTENSION_LIFECYCLE_MAPPINGS);
    if(configuratorsExtensionPoint != null) {
      IExtension[] configuratorExtensions = configuratorsExtensionPoint.getExtensions();
      for(IExtension extension : configuratorExtensions) {
        IConfigurationElement[] elements = extension.getConfigurationElements();
        for(IConfigurationElement element : elements) {
          if(element.getName().equals(ELEMENT_LIFECYCLE_MAPPING)) {
            if(mappingId.equals(element.getAttribute(ATTR_ID)))
              return createLifecycleMapping(element);
          }
        }
      }
    }
    return null;
  }

  public static AbstractProjectConfigurator getProjectConfigurator(String configuratorId) {
    return createProjectConfigurator(configuratorId, false/*bare*/);
  }

  private static AbstractProjectConfigurator createProjectConfigurator(String configuratorId, boolean bare) {
    IExtensionRegistry registry = Platform.getExtensionRegistry();
    IExtensionPoint configuratorsExtensionPoint = registry.getExtensionPoint(EXTENSION_PROJECT_CONFIGURATORS);
    if(configuratorsExtensionPoint != null) {
      IExtension[] configuratorExtensions = configuratorsExtensionPoint.getExtensions();
      for(IExtension extension : configuratorExtensions) {
        IConfigurationElement[] elements = extension.getConfigurationElements();
        for(IConfigurationElement element : elements) {
          if(element.getName().equals(ELEMENT_CONFIGURATOR)) {
            if(configuratorId.equals(element.getAttribute(AbstractProjectConfigurator.ATTR_ID))) {
              try {
                AbstractProjectConfigurator configurator = (AbstractProjectConfigurator) element
                    .createExecutableExtension(AbstractProjectConfigurator.ATTR_CLASS);

                MavenPlugin plugin = MavenPlugin.getDefault();
                configurator.setProjectManager(plugin.getMavenProjectManager());
                configurator.setMavenConfiguration(plugin.getMavenConfiguration());
                configurator.setMarkerManager(plugin.getMavenMarkerManager());
                configurator.setConsole(plugin.getConsole());

                if(!bare) {
                  for(IConfigurationElement pluginExecutionFilter : element
                      .getChildren(ELEMENT_PLUGIN_EXECUTION_FILTER)) {
                    configurator.addPluginExecutionFilter(createPluginExecutionFilter(pluginExecutionFilter));
                  }
                }

                return configurator;
              } catch(CoreException ex) {
                MavenLogger.log(ex);
              }
            }
          }
        }
      }
    }
    return null;
  }

  public static AbstractProjectConfigurator createProjectConfiguratorFor(IMavenProjectFacade mavenProjectFacade,
      MojoExecution mojoExecution) {
    for(LifecycleMappingMetadataSource lifecycleMappingMetadataSource : mavenProjectFacade
        .getLifecycleMappingMetadataSources()) {
      for(PluginExecutionMetadata pluginExecutionMetadata : lifecycleMappingMetadataSource.getPluginExecutions()) {
        if(pluginExecutionMetadata.getFilter().match(mojoExecution)) {
          return createProjectConfigurator(pluginExecutionMetadata);
        }
      }
    }
    return createProjectConfiguratorFor(mojoExecution);
  }

  private static AbstractProjectConfigurator createProjectConfiguratorFor(MojoExecution execution) {
    IExtensionRegistry registry = Platform.getExtensionRegistry();
    IExtensionPoint configuratorsExtensionPoint = registry.getExtensionPoint(EXTENSION_PROJECT_CONFIGURATORS);
    if(configuratorsExtensionPoint != null) {
      IExtension[] configuratorExtensions = configuratorsExtensionPoint.getExtensions();
      for(IExtension extension : configuratorExtensions) {
        IConfigurationElement[] elements = extension.getConfigurationElements();
        for(IConfigurationElement element : elements) {
          if(element.getName().equals(ELEMENT_CONFIGURATOR)) {
            if(isConfiguratorEnabledFor(element, execution)) {
              try {
                AbstractProjectConfigurator configurator = (AbstractProjectConfigurator) element
                    .createExecutableExtension(AbstractProjectConfigurator.ATTR_CLASS);

                MavenPlugin plugin = MavenPlugin.getDefault();
                configurator.setProjectManager(plugin.getMavenProjectManager());
                configurator.setMavenConfiguration(plugin.getMavenConfiguration());
                configurator.setMarkerManager(plugin.getMavenMarkerManager());
                configurator.setConsole(plugin.getConsole());

                for(IConfigurationElement mojo : element.getChildren(ELEMENT_PLUGIN_EXECUTION_FILTER)) {
                  configurator.addPluginExecutionFilter(createPluginExecutionFilter(mojo));
                }

                return configurator;
              } catch(CoreException ex) {
                MavenLogger.log(ex);
              }
            }
          }
        }
      }
    }
    return null;
  }

  private static boolean isConfiguratorEnabledFor(IConfigurationElement configuration, MojoExecution execution) {
    for(IConfigurationElement mojo : configuration.getChildren(ELEMENT_PLUGIN_EXECUTION_FILTER)) {
      if(createPluginExecutionFilter(mojo).match(execution)) {
        return true;
      }
    }
    return false;
  }

  public static List<LifecycleMappingMetadataSource> getLifecycleMappingMetadataSources(MavenProject mavenProject) {
    List<LifecycleMappingMetadataSource> lifecycleMappingMetadataSources = new ArrayList<LifecycleMappingMetadataSource>();

    PluginManagement pluginManagement = mavenProject.getPluginManagement();
    if(pluginManagement == null) {
      return lifecycleMappingMetadataSources;
    }

    Plugin metadataSourcesPlugin = pluginManagement.getPluginsAsMap().get(LifecycleMappingMetadataSource.PLUGIN_KEY); //$NON-NLS-1$
    if(metadataSourcesPlugin == null) {
      return lifecycleMappingMetadataSources;
    }

    Xpp3Dom configuration = (Xpp3Dom) metadataSourcesPlugin.getConfiguration();
    if(configuration == null) {
      return lifecycleMappingMetadataSources;
    }
    Xpp3Dom sources = configuration.getChild(LifecycleMappingMetadataSource.ELEMENT_SOURCES);
    if(sources == null) {
      return lifecycleMappingMetadataSources;
    }
    for(Xpp3Dom source : sources.getChildren(LifecycleMappingMetadataSource.ELEMENT_SOURCE)) {
      String groupId = null;
      Xpp3Dom child = source.getChild(ATTR_GROUPID);
      if(child != null) {
        groupId = child.getValue();
      }
      String artifactId = null;
      child = source.getChild(ATTR_ARTIFACTID);
      if(child != null) {
        artifactId = child.getValue();
      }
      String version = null;
      child = source.getChild(ATTR_VERSION);
      if(child != null) {
        version = child.getValue();
      }
      LifecycleMappingMetadataSource lifecycleMappingMetadataSource = LifecycleMappingFactory
          .getLifecycleMappingMetadataFromSource(groupId, artifactId, version,
          mavenProject.getRemoteArtifactRepositories());

      // Does this metadata override any other metadata?
      Iterator<LifecycleMappingMetadataSource> iter = lifecycleMappingMetadataSources.iterator();
      while(iter.hasNext()) {
        LifecycleMappingMetadataSource otherLifecycleMappingMetadata = iter.next();
        if(otherLifecycleMappingMetadata.getGroupId().equals(lifecycleMappingMetadataSource.getGroupId())
            && otherLifecycleMappingMetadata.getArtifactId().equals(lifecycleMappingMetadataSource.getArtifactId())) {
          iter.remove();
          break;
        }
      }

      lifecycleMappingMetadataSources.add(0, lifecycleMappingMetadataSource);
    }

    return lifecycleMappingMetadataSources;
  }

  // TODO: cache LifecycleMappingMetadataSource instances
  private static LifecycleMappingMetadataSource getLifecycleMappingMetadataFromSource(String groupId, String artifactId,
      String version,
      List<ArtifactRepository> repositories) {
    IMaven maven = MavenPlugin.getDefault().getMaven();
    try {
      Artifact artifact = maven.resolve(groupId, artifactId, version, "xml", LIFECYCLE_MAPPING_METADATA_CLASSIFIER,
          repositories, new NullProgressMonitor());

      File file = artifact.getFile();
      if(file == null || !file.exists() || !file.canRead()) {
        throw new LifecycleMappingConfigurationException("Cannot find file for artifact " + artifact);
      }
      try {
        return createLifecycleMappingMetadataSource(groupId, artifactId, version, file);
      } catch(IOException e) {
        throw new LifecycleMappingConfigurationException("Cannot read lifecycle mapping metadata for " + artifact, e);
      } catch(XmlPullParserException e) {
        throw new LifecycleMappingConfigurationException("Cannot parse lifecycle mapping metadata for " + artifact,
            e);
      } catch(RuntimeException e) {
        throw new LifecycleMappingConfigurationException("Cannot load lifecycle mapping metadata for " + artifact, e);
      }
    } catch(CoreException ex) {
      throw new LifecycleMappingConfigurationException(ex);
    }
  }

  private static LifecycleMappingMetadataSource createLifecycleMappingMetadataSource(String groupId, String artifactId,
      String version, File configuration) throws IOException, XmlPullParserException {
    InputStream in = new FileInputStream(configuration);
    try {
      LifecycleMappingMetadataSource lifecycleMappingMetadataSource = new LifecycleMappingMetadataSourceXpp3Reader()
          .read(in);
      lifecycleMappingMetadataSource.setGroupId(groupId);
      lifecycleMappingMetadataSource.setArtifactId(artifactId);
      lifecycleMappingMetadataSource.setVersion(version);
      return lifecycleMappingMetadataSource;
    } finally {
      IOUtil.close(in);
    }
  }

  private static void toXml(IConfigurationElement configurationElement, StringBuilder output) {
    output.append('<').append(configurationElement.getName());
    for(String attrName : configurationElement.getAttributeNames()) {
      String attrValue = configurationElement.getAttribute(attrName);
      if(attrValue != null) {
        output.append(' ').append(attrName).append("=\"").append(attrValue).append('"');
      }
    }
    output.append('>');
    String configurationElementValue = configurationElement.getValue();
    if(configurationElementValue != null) {
      output.append(configurationElementValue);
    }
    for(IConfigurationElement childElement : configurationElement.getChildren()) {
      toXml(childElement, output);
    }
    output.append("</").append(configurationElement.getName()).append('>');
  }

  private static String toXml(IConfigurationElement configurationElement) {
    if (configurationElement == null) {
      return null;
    }
    
    StringBuilder output = new StringBuilder();
    toXml(configurationElement, output);
    return output.toString();
  }
}
