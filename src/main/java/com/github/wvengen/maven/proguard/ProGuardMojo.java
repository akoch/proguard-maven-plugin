/**
 * Pyx4me framework
 * Copyright (C) 2006-2008 pyx4j.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 * @author vlads
 * @version $Id$
 */
package com.github.wvengen.maven.proguard;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Java;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.util.StringUtils;

/**
 *
 * <p>
 * The Obfuscate task provides a stand-alone obfuscation task
 * </p>
 *
 * @goal proguard
 * @phase package
 * @description Create small jar files using ProGuard
 * @requiresDependencyResolution compile
 * @threadSafe
 */

public class ProGuardMojo extends AbstractMojo
{

   public static final String MAPPING_FILE = "obfuscationMap.map";

   /**
    * Set this to 'true' to bypass ProGuard processing entirely.
    *
    * @parameter property="proguard.skip"
    */
   private boolean skip;

   /**
    * Recursively reads configuration options from the given file filename
    *
    * @parameter default-value="${basedir}/proguard.conf"
    */
   private File proguardInclude;

   /**
    * Select specific ProGuard version from plugin dependencies
    *
    * @parameter
    */
   private String proguardVersion;

   /**
    * ProGuard configuration options
    *
    * @parameter
    */
   private String[] options;

   /**
    * Specifies not to obfuscate the input class files.
    *
    * @parameter default-value="true"
    */
   private boolean obfuscate;

   /**
    * Specifies that project compile dependencies be added as -libraryjars to proguard arguments. Dependency itself is
    * not included in resulting jar unless you set includeDependencyInjar to true
    *
    * @parameter default-value="true"
    */
   private boolean includeDependency;

   /**
    * Specifies that project compile dependencies should be added as injar.
    *
    * @parameter default-value="false"
    */
   private boolean includeDependencyInjar;

   /**
    * Bundle project dependency to resulting jar. Specifies list of artifact inclusions
    *
    * @parameter
    */
   private Assembly assembly;

   /**
    * Configuration to use unique mappings for classes in a multi module build
    *
    * @parameter
    */
   private UniqueMapping uniqueMapping;

   /**
    * Additional -libraryjars e.g. ${java.home}/lib/rt.jar Project compile dependency are added automatically. See
    * exclusions
    *
    * @parameter
    */
   private List<String> libs;

   /**
    * List of dependency exclusions
    *
    * @parameter
    */
   private List<String> exclusions;

   /**
    * Specifies the input jar name (or wars, ears, zips) of the application to be
    * processed.
    *
    * You may specify a classes directory e.g. 'classes'. This way plugin will processed
    * the classes instead of jar. You would need to bind the execution to phase 'compile'
    * or 'process-classes' in this case.
    *
    * @parameter expression="${project.build.finalName}.jar"
    * @required
    */
   protected String injar;

   /**
    * Set this to 'true' to bypass ProGuard processing when injar does not exists.
    *
    * @parameter default-value="false"
    */
   private boolean injarNotExistsSkip;

   /**
    * Apply ProGuard classpathentry Filters to input jar. e.g. <code>!**.gif,!**&#47;tests&#47;**'</code>
    *
    * @parameter
    */
   protected String inFilter;

   /**
    * Specifies the names of the output jars. If attach=true the value ignored and name constructed base on classifier
    * If empty input jar would be overdriven.
    *
    * @parameter
    */
   protected String outjar;

   /**
    * Specifies whether or not to attach the created artifact to the project
    *
    * @parameter default-value="false"
    */
   private final boolean attach = false;

   /**
    * Specifies attach artifact type
    *
    * @parameter default-value="jar"
    */
   private String attachArtifactType;

   /**
    * Specifies attach artifact Classifier, Ignored if attach=false
    *
    * @parameter default-value="small"
    */
   private String attachArtifactClassifier;

   /**
    * Set to false to exclude the attachArtifactClassifier from the Artifact final name. Default value is true.
    *
    * @parameter default-value="true"
    */
   private boolean appendClassifier;

   /**
    * Set to true to include META-INF/maven/** maven descriptord
    *
    * @parameter default-value="false"
    */
   private boolean addMavenDescriptor;

   /**
    * Directory containing the input and generated JAR.
    *
    * @parameter property="project.build.directory"
    * @required
    */
   protected File outputDirectory;

   /**
    * The Maven project reference where the plugin is currently being executed. The default value is populated from
    * maven.
    *
    * @parameter property="project"
    * @readonly
    * @required
    */
   protected MavenProject mavenProject;

   /**
    * The plugin dependencies.
    *
    * @parameter property="plugin.artifacts"
    * @required
    * @readonly
    */
   protected List<String> pluginArtifacts;

   /**
    * @component
    */
   private MavenProjectHelper projectHelper;

   /**
    * The Jar archiver.
    *
    * @component role="org.codehaus.plexus.archiver.Archiver" roleHint="jar"
    * @required
    */
   private JarArchiver jarArchiver;

   /**
    * The maven archive configuration to use. only if assembly is used.
    *
    * @parameter
    */
   protected MavenArchiveConfiguration archive = new MavenArchiveConfiguration();

   /**
    * The max memory the forked java process should use, e.g. 256m
    *
    * @parameter
    */
   protected String maxMemory;

   /**
    * ProGuard main class name.
    *
    * @parameter default-value="proguard.ProGuard"
    */
   protected String proguardMainClass = "proguard.ProGuard";

   private Log log;

   /**
    * ProGuard docs: Names with special characters like spaces and parentheses must be quoted with single or double
    * quotes.
    */
   private static String fileNameToString(final String fileName)
   {
      return "'" + fileName + "'";
   }

   private static String fileToString(final File file)
   {
      return fileNameToString(file.toString());
   }

   private boolean useArtifactClassifier()
   {
      return appendClassifier && attachArtifactClassifier != null && attachArtifactClassifier.length() > 0;
   }

   public void execute() throws MojoExecutionException, MojoFailureException
   {

      log = getLog();

      if (skip)
      {
         log.info("Bypass ProGuard processing because \"proguard.skip=true\"");
         return;
      }

      final ArrayList<String> args = new ArrayList<String>();
      File mappingFile = null;
      if (uniqueMapping != null)
      {
         MavenProject parent = mavenProject.getParent();
         final int projectIndex;
         if (uniqueMapping.mappingFile == null)
         {
            if (parent != null)
            {
               while (parent.getParent() != null)
               {
                  parent = parent.getParent();
               }
               final File projectFolder = parent.getFile().getParentFile();
               mappingFile = new File(new File(projectFolder, "target"), MAPPING_FILE);
               final List collectedProjects = parent.getCollectedProjects();
               projectIndex = collectedProjects == null ? 0 : collectedProjects.indexOf(mavenProject);
            }
            else
            {
               mappingFile = new File(outputDirectory, MAPPING_FILE);
               projectIndex = 0;
            }
         }
         else
         {
            mappingFile = new File(outputDirectory, uniqueMapping.mappingFile);
            projectIndex = 0;
         }

         if (uniqueMapping.onlySubmodules)
         {
            if (parent == null)
            {
               if (mappingFile.exists())
               {
                  // delete existing mapping file here
                  mappingFile.delete();
               }
               return;
            }
         }

         if (mappingFile.exists())
         {
            args.add("-applymapping");
            args.add(fileToString(mappingFile));
         }

         args.add("-flattenpackagehierarchy");

         final StringBuilder packageNameBuilder = new StringBuilder();
         packageNameBuilder.append(uniqueMapping.packageName);
         packageNameBuilder.append(".");
         packageNameBuilder.append(Character.toChars('a' + projectIndex));
         args.add(packageNameBuilder.toString());
      }

      final boolean mainIsJar = mavenProject.getPackaging().equals("jar");

      File inJarFile = new File(outputDirectory, injar);
      if (!inJarFile.exists())
      {
         if (injarNotExistsSkip)
         {
            log.info("Bypass ProGuard processing because \"injar\" dos not exist");
            return;
         }
         else if (mainIsJar)
         {
            throw new MojoFailureException("Can't find file " + inJarFile);
         }
      }

      if (!outputDirectory.exists())
      {
         if (!outputDirectory.mkdirs())
         {
            throw new MojoFailureException("Can't create " + outputDirectory);
         }
      }

      File outJarFile;
      boolean sameArtifact;

      if (attach)
      {
         outjar = nameNoType(injar);
         if (useArtifactClassifier())
         {
            outjar += "-" + attachArtifactClassifier;
         }
         outjar += "." + attachArtifactType;
      }

      if (outjar != null && !outjar.equals(injar))
      {
         sameArtifact = false;
         outJarFile = new File(outputDirectory, outjar).getAbsoluteFile();
         if (outJarFile.exists())
         {
            if (!deleteFileOrDirectory(outJarFile))
            {
               throw new MojoFailureException("Can't delete " + outJarFile);
            }
         }
      }
      else
      {
         sameArtifact = true;
         outJarFile = inJarFile.getAbsoluteFile();
         File baseFile;
         if (inJarFile.isDirectory())
         {
            baseFile = new File(outputDirectory, nameNoType(injar) + "-proguard_base");
         }
         else
         {
            baseFile = new File(outputDirectory, nameNoType(injar) + "-proguard_base.jar");
         }
         if (baseFile.exists())
         {
            if (!deleteFileOrDirectory(baseFile))
            {
               throw new MojoFailureException("Can't delete " + baseFile);
            }
         }
         if (inJarFile.exists())
         {
            if (!inJarFile.renameTo(baseFile))
            {
               throw new MojoFailureException("Can't rename " + inJarFile);
            }
         }
         inJarFile = baseFile;
      }

      if (log.isDebugEnabled())
      {
         @SuppressWarnings("unchecked")
         final List<Artifact> dependancy = mavenProject.getCompileArtifacts();
         for (final Iterator<Artifact> i = dependancy.iterator(); i.hasNext();)
         {
            final Artifact artifact = i.next();
            log.debug("--- compile artifact " + artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                  + artifact.getType() + ":" + artifact.getClassifier() + " Scope:" + artifact.getScope());
         }
         for (final Iterator i = mavenProject.getArtifacts().iterator(); i.hasNext();)
         {
            final Artifact artifact = (Artifact)i.next();
            log.debug("--- artifact " + artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                  + artifact.getType() + ":" + artifact.getClassifier() + " Scope:" + artifact.getScope());
         }
         for (final Iterator i = mavenProject.getDependencies().iterator(); i.hasNext();)
         {
            final Dependency artifact = (Dependency)i.next();
            log.debug("--- dependency " + artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                  + artifact.getType() + ":" + artifact.getClassifier() + " Scope:" + artifact.getScope());
         }
      }

      final Collection<String> libraryJars = new HashSet<String>();
      final Set<String> inPath = new HashSet<String>();
      boolean hasInclusionLibrary = false;
      if (assembly != null)
      {
         for (final Iterator iter = assembly.inclusions.iterator(); iter.hasNext();)
         {
            final Inclusion inc = (Inclusion)iter.next();
            if (!inc.library)
            {
               final File file = getClasspathElement(getDependancy(inc, mavenProject), mavenProject);
               inPath.add(file.toString());
               log.debug("--- ADD injars:" + inc.artifactId);
               final StringBuffer filter = new StringBuffer(fileToString(file));
               filter.append("(!META-INF/MANIFEST.MF");
               if (!addMavenDescriptor)
               {
                  filter.append(",");
                  filter.append("!META-INF/maven/**");
               }
               if (inc.filter != null)
               {
                  filter.append(",").append(inc.filter);
               }
               filter.append(")");
               args.add("-injars");
               args.add(filter.toString());
            }
            else
            {
               hasInclusionLibrary = true;
               log.debug("--- ADD libraryjars:" + inc.artifactId);
               // This may not be CompileArtifacts, maven 2.0.6 bug
               final File file = getClasspathElement(getDependancy(inc, mavenProject), mavenProject);
               inPath.add(file.toString());
               libraryJars.add(fileToString(file));
            }
         }
      }

      if (inJarFile.exists())
      {
         args.add("-injars");
         final StringBuffer filter = new StringBuffer(fileToString(inJarFile));
         if (inFilter != null || !addMavenDescriptor)
         {
            filter.append("(");
            boolean coma = false;

            if (!addMavenDescriptor)
            {
               coma = true;
               filter.append("!META-INF/maven/**");
            }

            if (inFilter != null)
            {
               if (coma)
               {
                  filter.append(",");
               }
               filter.append(inFilter);
            }

            filter.append(")");
         }
         args.add(filter.toString());
      }

      if (includeDependency)
      {
         final List dependency = this.mavenProject.getCompileArtifacts();
         for (final Iterator i = dependency.iterator(); i.hasNext();)
         {
            final Artifact artifact = (Artifact)i.next();
            // dependency filter
            if (isExclusion(artifact))
            {
               continue;
            }
            final File file = getClasspathElement(artifact, mavenProject);

            if (inPath.contains(file.toString()))
            {
               log.debug("--- ignore library since one in injar:" + artifact.getArtifactId());
               continue;
            }
            if (includeDependencyInjar)
            {
               log.debug("--- ADD library as injars:" + artifact.getArtifactId());
               args.add("-injars");
               args.add(fileToString(file));
            }
            else
            {
               log.debug("--- ADD libraryjars:" + artifact.getArtifactId());
               libraryJars.add(fileToString(file));
            }
         }
      }

      if (args.contains("-injars"))
      {
         args.add("-outjars");
         args.add(fileToString(outJarFile));
      }

      if (!obfuscate)
      {
         args.add("-dontobfuscate");
      }

      if (proguardInclude != null)
      {
         if (proguardInclude.exists())
         {
            args.add("-include");
            args.add(fileToString(proguardInclude));
            log.debug("proguardInclude " + proguardInclude);
         }
         else
         {
            log.debug("proguardInclude config does not exists " + proguardInclude);
         }
      }

      if (libs != null)
      {
         for (final Iterator i = libs.iterator(); i.hasNext();)
         {
            final Object lib = i.next();
            libraryJars.add(fileNameToString(lib.toString()));
         }
      }

      args.add("-printmapping");
      final File printMappingFile = new File(outputDirectory, "proguard_map.txt").getAbsoluteFile();
      args.add(fileToString(printMappingFile));
      args.add("-printseeds");
      args.add(fileToString(new File(outputDirectory, "proguard_seeds.txt").getAbsoluteFile()));
      args.add("-printconfiguration");
      args.add(fileToString(new File(outputDirectory, "proguard_conf.txt").getAbsoluteFile()));
      args.add("-dump");
      args.add(fileToString(new File(outputDirectory, "proguard_dump.txt").getAbsoluteFile()));
      args.add("-printusage");
      args.add(fileToString(new File(outputDirectory, "proguard_usage.txt").getAbsoluteFile()));

      if (log.isDebugEnabled())
      {
         args.add("-verbose");
      }

      if (options != null)
      {
         for (int i = 0; i < options.length; i++)
         {
            args.add(options[i]);
         }
      }

      log.info("execute ProGuard " + args.toString());
      try
      {
         proguardMain(getProguardJar(this), fillArgsWithLibraries(args, libraryJars), this);
      }
      catch (final BuildException ex)
      {
         if (ex.getCause() instanceof IOException)
         {
            log.info("Probably the run command is too long. Therefor the command is shortened and executed again.");
            final Collection<String> resultingLibraries = new HashSet<String>();
            for (final String libraryJar : libraryJars)
            {
               final int countMatches = StringUtils.countMatches(libraryJar, "jar");
               final int index1 = libraryJar.lastIndexOf('/');
               final int index2 = libraryJar.lastIndexOf('\\');
               final int index = Math.max(index1, index2);
               if (countMatches <= 1 && index > 0)
               {
                  resultingLibraries.add(libraryJar.substring(0, index + 1) + "'");
               }
               else
               {
                  resultingLibraries.add(libraryJar);
               }
            }

            proguardMain(getProguardJar(this), fillArgsWithLibraries(args, resultingLibraries), this);
         }
      }

      new FixAfterObfuscation(getLog()).process(outJarFile, printMappingFile);

      if (assembly != null && hasInclusionLibrary)
      {

         log.info("creating assembly");

         final File baseFile = new File(outputDirectory, nameNoType(injar) + "-proguard_result.jar");
         if (baseFile.exists())
         {
            if (!baseFile.delete())
            {
               throw new MojoFailureException("Can't delete " + baseFile);
            }
         }
         final File archiverFile = outJarFile.getAbsoluteFile();
         if (!outJarFile.renameTo(baseFile))
         {
            throw new MojoFailureException("Can't rename " + outJarFile);
         }

         final MavenArchiver archiver = new MavenArchiver();
         archiver.setArchiver(jarArchiver);
         archiver.setOutputFile(archiverFile);
         archive.setAddMavenDescriptor(addMavenDescriptor);

         try
         {
            jarArchiver.addArchivedFileSet(baseFile);

            for (final Iterator iter = assembly.inclusions.iterator(); iter.hasNext();)
            {
               final Inclusion inc = (Inclusion)iter.next();
               if (inc.library)
               {
                  File file;
                  final Artifact artifact = getDependancy(inc, mavenProject);
                  file = getClasspathElement(artifact, mavenProject);
                  if (file.isDirectory())
                  {
                     getLog().info("merge project: " + artifact.getArtifactId() + " " + file);
                     jarArchiver.addDirectory(file);
                  }
                  else
                  {
                     getLog().info("merge artifact: " + artifact.getArtifactId());
                     jarArchiver.addArchivedFileSet(file);
                  }
               }
            }

            archiver.createArchive(mavenProject, archive);

         }
         catch (final Exception e)
         {
            throw new MojoExecutionException("Unable to create jar", e);
         }

      }

      if (attach && !sameArtifact)
      {
         if (useArtifactClassifier())
         {
            projectHelper.attachArtifact(mavenProject, attachArtifactType, attachArtifactClassifier, outJarFile);
         }
         else
         {
            projectHelper.attachArtifact(mavenProject, attachArtifactType, null, outJarFile);
         }
      }

      // append new mappings to existing mapping file
      FileReader mappingReader = null;
      FileWriter mappingWriter = null;
      try
      {
         createIfNecessary(mappingFile);

         mappingWriter = new FileWriter(mappingFile, true);
         mappingReader = new FileReader(printMappingFile);

         int input;
         while ((input = mappingReader.read()) != -1)
         {
            mappingWriter.write(input);
         }
      }
      catch (final IOException e)
      {
         throw new MojoExecutionException("Unable to create mapping file", e);
      }
      finally
      {
         try
         {
            if (mappingWriter != null)
            {
               mappingWriter.close();
            }
            if (mappingReader != null)
            {
               mappingReader.close();
            }
         }
         catch (final IOException e)
         {
            // if it can't be closed, it can't be closed ...
         }
      }
   }

   private ArrayList<String> fillArgsWithLibraries(final Collection<String> args, final Collection<String> libraries)
   {
      final ArrayList<String> runArgs = new ArrayList<String>(args);
      for (final String libraryJar : libraries)
      {
         runArgs.add("-libraryjars");
         runArgs.add(libraryJar);
      }
      return runArgs;
   }

   private void createIfNecessary(final File mappingFile) throws IOException
   {
      if (mappingFile != null)
      {
         if (!mappingFile.exists())
         {
            mappingFile.getParentFile().mkdirs();
            mappingFile.createNewFile();
         }
      }
   }

   private static File getProguardJar(final ProGuardMojo mojo) throws MojoExecutionException
   {

      Artifact proguardArtifact = null;
      int proguardArtifactDistance = -1;
      // This should be solved in Maven 2.1
      for (final Iterator i = mojo.pluginArtifacts.iterator(); i.hasNext();)
      {
         final Artifact artifact = (Artifact)i.next();
         mojo.getLog().debug("pluginArtifact: " + artifact.getFile());
         if (artifact.getArtifactId().startsWith("proguard") &&
               !artifact.getArtifactId().startsWith("proguard-maven-plugin"))
         {
            final int distance = artifact.getDependencyTrail().size();
            mojo.getLog().debug("proguard DependencyTrail: " + distance);
            if (mojo.proguardVersion != null && mojo.proguardVersion.equals(artifact.getVersion()))
            {
               proguardArtifact = artifact;
               break;
            }
            else if (proguardArtifactDistance == -1)
            {
               proguardArtifact = artifact;
               proguardArtifactDistance = distance;
            }
            else if (distance < proguardArtifactDistance)
            {
               proguardArtifact = artifact;
               proguardArtifactDistance = distance;
            }
         }
      }
      if (proguardArtifact != null)
      {
         mojo.getLog().debug("proguardArtifact: " + proguardArtifact.getFile());
         return proguardArtifact.getFile().getAbsoluteFile();
      }
      mojo.getLog().info("proguard jar not found in pluginArtifacts");

      ClassLoader cl;
      cl = mojo.getClass().getClassLoader();
      // cl = Thread.currentThread().getContextClassLoader();
      final String classResource = "/" + mojo.proguardMainClass.replace('.', '/') + ".class";
      final URL url = cl.getResource(classResource);
      if (url == null)
      {
         throw new MojoExecutionException("Obfuscation failed ProGuard (" + mojo.proguardMainClass
               + ") not found in classpath");
      }
      String proguardJar = url.toExternalForm();
      if (proguardJar.startsWith("jar:file:"))
      {
         proguardJar = proguardJar.substring("jar:file:".length());
         proguardJar = proguardJar.substring(0, proguardJar.indexOf('!'));
      }
      else
      {
         throw new MojoExecutionException("Unrecognized location (" + proguardJar + ") in classpath");
      }
      return new File(proguardJar);
   }

   private static void proguardMain(final File proguardJar, final ArrayList argsList, final ProGuardMojo mojo)
         throws MojoExecutionException
   {

      final Java java = new Java();

      final Project antProject = new Project();
      antProject.setName(mojo.mavenProject.getName());
      antProject.init();

      final DefaultLogger antLogger = new DefaultLogger();
      antLogger.setOutputPrintStream(System.out);
      antLogger.setErrorPrintStream(System.err);
      antLogger.setMessageOutputLevel(mojo.log.isDebugEnabled() ? Project.MSG_DEBUG : Project.MSG_INFO);

      antProject.addBuildListener(antLogger);
      antProject.setBaseDir(mojo.mavenProject.getBasedir());

      java.setProject(antProject);
      java.setTaskName("proguard");

      mojo.getLog().info("proguard jar: " + proguardJar);

      java.createClasspath().setLocation(proguardJar);
      // java.createClasspath().setPath(System.getProperty("java.class.path"));
      java.setClassname(mojo.proguardMainClass);

      java.setFailonerror(true);

      java.setFork(true);

      // get the maxMemory setting
      if (mojo.maxMemory != null)
      {
         java.setMaxmemory(mojo.maxMemory);
      }

      for (final Iterator i = argsList.iterator(); i.hasNext();)
      {
         java.createArg().setValue(i.next().toString());
      }

      final int result = java.executeJava();
      if (result != 0)
      {
         throw new MojoExecutionException("Obfuscation failed (result=" + result + ")");
      }
   }

   private static String nameNoType(final String fileName)
   {
      final int extStart = fileName.lastIndexOf('.');
      if (extStart == -1)
      {
         return fileName;
      }
      return fileName.substring(0, extStart);
   }

   private static boolean deleteFileOrDirectory(final File path) throws MojoFailureException
   {
      if (path.isDirectory())
      {
         final File[] files = path.listFiles();
         for (int i = 0; i < files.length; i++)
         {
            if (files[i].isDirectory())
            {
               if (!deleteFileOrDirectory(files[i]))
               {
                  throw new MojoFailureException("Can't delete dir " + files[i]);
               }
            }
            else
            {
               if (!files[i].delete())
               {
                  throw new MojoFailureException("Can't delete file " + files[i]);
               }
            }
         }
         return path.delete();
      }
      else
      {
         return path.delete();
      }
   }

   private static Artifact getDependancy(final Inclusion inc, final MavenProject mavenProject)
         throws MojoExecutionException
   {
      final Set dependancy = mavenProject.getArtifacts();
      for (final Iterator i = dependancy.iterator(); i.hasNext();)
      {
         final Artifact artifact = (Artifact)i.next();
         if (inc.match(artifact))
         {
            return artifact;
         }
      }
      throw new MojoExecutionException("artifactId Not found " + inc.artifactId);
   }

   private boolean isExclusion(final Artifact artifact)
   {
      if (exclusions == null)
      {
         return false;
      }
      for (final Iterator iter = exclusions.iterator(); iter.hasNext();)
      {
         final Exclusion excl = (Exclusion)iter.next();
         if (excl.match(artifact))
         {
            return true;
         }
      }
      return false;
   }

   private static File getClasspathElement(final Artifact artifact, final MavenProject mavenProject)
         throws MojoExecutionException
   {
      if (artifact.getClassifier() != null)
      {
         return artifact.getFile();
      }
      final String refId = artifact.getGroupId() + ":" + artifact.getArtifactId();
      final String refIdWithVersion = refId + ":" + artifact.getVersion();
      MavenProject project = (MavenProject)mavenProject.getProjectReferences().get(refIdWithVersion);
      if (project == null)
      {
         project = (MavenProject)mavenProject.getProjectReferences().get(refId);
      }
      if (project != null)
      {
         return new File(project.getBuild().getOutputDirectory());
      }
      else
      {
         final File file = artifact.getFile();
         if (file == null || !file.exists())
         {
            throw new MojoExecutionException("Dependency Resolution Required " + artifact);
         }
         return file;
      }
   }
}
