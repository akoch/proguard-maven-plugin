package com.github.wvengen.maven.proguard;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import proguard.obfuscate.MappingProcessor;
import proguard.obfuscate.MappingReader;

public class FixAfterObfuscation {

	private static final String EXPORT_PACKAGE = "Export-Package";
	private static final String EXPORT_PACKAGE_ATTRIBUTE_SEPARATOR = ": ";

	private Log log;

	public FixAfterObfuscation(Log log) {
		this.log = log;
	}

	private static void collectFiles(File[] files, List<File> containedFiles) {
		for (File file : files) {
			if (file.isDirectory()) {
				collectFiles(file.listFiles(), containedFiles);
			} else if (file.exists()) {
				containedFiles.add(file);
			}
		}
	}

	private static void deleteFile(File file) {
		if (file.isDirectory()) {
			for (File childFile : file.listFiles()) {
				deleteFile(childFile);
			}
		}
		file.delete();
	}

	private static String getNormalizedPath(File file) {
		return file.getAbsolutePath().replaceAll("\\\\", "/");
	}

	private void extractObfuscatedJar(File obfuscatedJar, File targetDir) throws MojoExecutionException {
		if (obfuscatedJar.exists()) {
			if (targetDir.exists()) {
				deleteFile(targetDir);
			}
			targetDir.mkdirs();
			ZipInputStream inputStream = null;
			FileOutputStream outStream = null;
			try {
				inputStream = new ZipInputStream(new FileInputStream(obfuscatedJar));
				ZipEntry zipEntry;
				while ((zipEntry = inputStream.getNextEntry()) != null) {
					File targetFile = new File(targetDir, zipEntry.getName());
					if (zipEntry.isDirectory()) {
						targetFile.mkdirs();
						continue;
					}
					File parentFile = targetFile.getParentFile();
					if (!parentFile.exists()) {
						parentFile.mkdirs();
					}

					// extract the zip entry
					outStream = new FileOutputStream(targetFile);
					byte[] b = new byte[2048];
					int len;
					while ((len = inputStream.read(b)) != -1) {
						outStream.write(b, 0, len);
					}
					outStream.close();
				}
			} catch (FileNotFoundException e) {
				throw new MojoExecutionException(String.format("The jar %s does not exist", obfuscatedJar.getAbsolutePath()));
			} catch (IOException e) {
				throw new MojoExecutionException(String.format("Obfuscated jar cannot be extracted", obfuscatedJar.getAbsolutePath()), e);
			} finally {
				try {
					if (inputStream != null) {
						inputStream.close();
					}
				} catch (Exception e) {
					log.info("Jar file reader stream could not be closed correctly");
				}
			}
		} else {
			throw new IllegalArgumentException("Obfuscated jar does not exist");
		}
	}

	private void fixManifest(File targetDir, ProGuardObfuscationMapping mapping) throws MojoExecutionException {
		File manifestFile = new File(targetDir, "META-INF" + File.separator + "MANIFEST.MF");
		if (manifestFile.exists()) {
			BufferedReader fileReader = null;
			BufferedWriter fileWriter = null;
			try {
				fileReader = new BufferedReader(new FileReader(manifestFile));

				Collection<String> obfuscatedLines = new LinkedList<String>();

				String currentAttribute = null;
				StringBuilder currentAttributeValue = new StringBuilder();

				String line;
				while ((line = fileReader.readLine()) != null) {
					// obfuscatedLines.add(processLine(line.toString(),
					// mapping));
					if (line.contains(EXPORT_PACKAGE_ATTRIBUTE_SEPARATOR)) {
						// new attribute found
						// evaluate last pair, if found
						if (currentAttribute != null) {
							obfuscatedLines.add(processLine(currentAttribute, currentAttributeValue.toString(), mapping));
						}

						// start collecting for new attribute
						currentAttributeValue = new StringBuilder();
						String[] splittedLine = line.split(EXPORT_PACKAGE_ATTRIBUTE_SEPARATOR);
						if (splittedLine.length > 2) {
							throw new IllegalArgumentException("Unsupported Manifest format");
						}
						currentAttribute = splittedLine[0];
						currentAttributeValue.append(splittedLine[1].trim());
					} else {
						currentAttributeValue.append(line.trim());
					}
				}
				fileWriter = new BufferedWriter(new FileWriter(manifestFile));
				for (String obfuscatedLine : obfuscatedLines) {
					int startPos = 0;
					int endPos = 70;
					while(startPos < obfuscatedLine.length()) {
						if(startPos>0) {
							fileWriter.write(" ");
						}
						fileWriter.write(obfuscatedLine.substring(startPos, Math.min(obfuscatedLine.length(), endPos)));
						fileWriter.newLine();
						startPos = endPos;
						endPos+=69;
					}
				}
			} catch (FileNotFoundException e) {
				// should not happen
				throw new MojoExecutionException("The manifest file does not exist", e);
			} catch (IOException e) {
				throw new MojoExecutionException("The manifest file cannot be processed", e);
			} finally {
				try {
					if (fileReader != null) {
						fileReader.close();
					}
				} catch (IOException e) {
					log.info("Manifest reader stream could not be closed correctly");
				}
				try {
					if (fileWriter != null) {
						fileWriter.close();
					}
				} catch (IOException e) {
					log.info("Manifest writer stream could not be closed correctly");
				}
			}
		}
	}

	private String processLine(String attribute, String attributeValue, ProGuardObfuscationMapping mapping) {
		StringBuilder sb = new StringBuilder();
		if (EXPORT_PACKAGE.equals(attribute)) {
			for (String packageToObfuscate : attributeValue.split(",")) {
				for (String obfuscatedPackage : mapping.getObfuscatedPackages(packageToObfuscate)) {
					if (sb.length() > 0) {
						sb.append(",");
					} else {
						sb.append(attribute);
						sb.append(EXPORT_PACKAGE_ATTRIBUTE_SEPARATOR);
					}
					sb.append(obfuscatedPackage);
				}
			}
			return sb.toString();
		} else {
			sb.append(attribute);
			sb.append(EXPORT_PACKAGE_ATTRIBUTE_SEPARATOR);
			sb.append(attributeValue);
		}
		return sb.toString();
	}

	private void rebuildObfuscatedJar(File sourceDir, File obfuscatedJar) {
		File obfuscatedTmp = new File(obfuscatedJar + ".tmp");

		List<File> containedFiles = new LinkedList<File>();
		collectFiles(sourceDir.listFiles(), containedFiles);

		try {
			ZipOutputStream outZip = new ZipOutputStream(new FileOutputStream(obfuscatedTmp));
			for (File containedFile : containedFiles) {
				FileInputStream in = new FileInputStream(containedFile);
				outZip.putNextEntry(new ZipEntry(getNormalizedPath(containedFile).replaceAll(getNormalizedPath(sourceDir) + "/", "")));
				byte[] buffer = new byte[4096];
				int buffered;
				while ((buffered = in.read(buffer)) > 0) {
					outZip.write(buffer, 0, buffered);
				}
				outZip.closeEntry();
				in.close();
			}
			outZip.close();
		} catch (IOException e) {
		}

		if (obfuscatedJar.exists()) {
			obfuscatedJar.delete();
		}
		// move newly created jar to old name
		obfuscatedTmp.renameTo(obfuscatedJar);
	}

	public void process(File obfuscatedJar, File mappingFile) throws MojoExecutionException {
		String jarName = obfuscatedJar.getName();
		// remove .jar file ending
		jarName = jarName.substring(0, jarName.length() - 4);
		File targetDir = new File(obfuscatedJar.getParentFile(), jarName);
		ProGuardObfuscationMapping mapping = new ProGuardObfuscationMapping(mappingFile);

		extractObfuscatedJar(obfuscatedJar, targetDir);
		fixManifest(targetDir, mapping);
		rebuildObfuscatedJar(targetDir, obfuscatedJar);
	}

	private static class ProGuardObfuscationMapping implements MappingProcessor {

		private Map<String, String> origToObfuscatedClasses;
		private Map<String, Collection<String>> origToObfuscatedPackages;

		public ProGuardObfuscationMapping(File mappingFile) throws MojoExecutionException {
			MappingReader mappingReader = new MappingReader(mappingFile);
			try {
				origToObfuscatedClasses = new HashMap<String, String>();
				origToObfuscatedPackages = new HashMap<String, Collection<String>>();
				mappingReader.pump(this);
			} catch (IOException e) {
				throw new MojoExecutionException("Mapping file could not be read", e);
			}
		}

		public Collection<String> getObfuscatedPackages(String packageToObfuscate) {
			Collection<String> obfuscatedPackages = origToObfuscatedPackages.get(packageToObfuscate);
			if (obfuscatedPackages == null) {
				// if no obfuscation informations can be found, return the
				// given package
				return Collections.singleton(packageToObfuscate);
			}
			return obfuscatedPackages;
		}

		public boolean processClassMapping(String className, String obfuscatedClassName) {
			origToObfuscatedClasses.put(className, obfuscatedClassName);

			String packageName = className.substring(0, className.lastIndexOf('.'));
			String obfuscatedPackageName = obfuscatedClassName.substring(0, obfuscatedClassName.lastIndexOf('.'));

			Collection<String> obfuscatedPackages = origToObfuscatedPackages.get(packageName);
			if (obfuscatedPackages == null) {
				obfuscatedPackages = new HashSet<String>();
				origToObfuscatedPackages.put(packageName, obfuscatedPackages);
			}
			obfuscatedPackages.add(obfuscatedPackageName);
			return true;
		}

		public void processFieldMapping(String arg0, String arg1, String arg2, String arg3) {
			// do nothing
		}

		public void processMethodMapping(String arg0, int arg1, int arg2, String arg3, String arg4, String arg5, String arg6) {
			// do nothing
		}

	}
}
