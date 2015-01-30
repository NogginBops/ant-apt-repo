/**
 * Copyright (c) 2015, technologist.kj@gmail.com, theo@m1theo.org.
 * 
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.codemarvels.ant.aptrepotask;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.LogLevel;
import org.codehaus.plexus.util.FileUtils;

import com.codemarvels.ant.aptrepotask.packages.PackageEntry;
import com.codemarvels.ant.aptrepotask.packages.Packages;
import com.codemarvels.ant.aptrepotask.release.Release;
import com.codemarvels.ant.aptrepotask.release.ReleaseInfo;
import com.codemarvels.ant.aptrepotask.utils.ControlHandler;
import com.codemarvels.ant.aptrepotask.utils.DefaultHashes;
import com.codemarvels.ant.aptrepotask.utils.Utils;

public class AptRepoTask extends Task {
	private static final String RELEASE = "Release";
	private static final String PACKAGES_GZ = "Packages.gz";
	private static final String FAILED_TO_CREATE_APT_REPO = "Failed to create apt-repo: ";
	private static final String CONTROL_FILE_NAME = "control";
	private static final String FILE_DEB_EXT = ".deb";
	private BufferedWriter packagesWriter;
	/**
	 * Location of the apt repository.
	 */
	private String repoDir;

	public String getRepoDir() {
		return repoDir;
	}

	public void setRepoDir(String repoDir) {
		this.repoDir = repoDir;
	}

	public void execute() {
		if(repoDir==null){
			log("repoDir attribute is empty !", LogLevel.ERR.getLevel());
			throw new RuntimeException("Bad attributes for apt-repo task");
		}
		log("repo dir: " + repoDir);
		File repoFolder = new File(repoDir);
		if (!repoFolder.exists()) {
			repoFolder.mkdirs();
		}
		File[] files = repoFolder.listFiles(new FileFilter() {
			public boolean accept(File pathname) {
				if (pathname.getName().endsWith(FILE_DEB_EXT)) {
					return true;
				}
				return false;
			}
		});
		Packages packages = new Packages();
		for (int i = 0; i < files.length; i++) {
			File file = files[i];
			PackageEntry packageEntry = new PackageEntry();
			packageEntry.setSize(file.length());
			packageEntry.setSha1(Utils.getDigest("SHA-1", file));
			packageEntry.setSha256(Utils.getDigest("SHA-256", file));
			packageEntry.setMd5sum(Utils.getDigest("MD5", file));
			String fileName = file.getName();
			packageEntry.setFilename(fileName);
			log("found deb: " + fileName);
			try {
				ArchiveInputStream control_tgz;
				ArArchiveEntry entry;
				TarArchiveEntry control_entry;
				ArchiveInputStream debStream = new ArchiveStreamFactory().createArchiveInputStream("ar", new FileInputStream(file));
				while ((entry = (ArArchiveEntry) debStream.getNextEntry()) != null) {
					if (entry.getName().equals("control.tar.gz")) {
						ControlHandler controlHandler = new ControlHandler();
						GZIPInputStream gzipInputStream = new GZIPInputStream(debStream);
						control_tgz = new ArchiveStreamFactory().createArchiveInputStream("tar",gzipInputStream);
						while ((control_entry = (TarArchiveEntry) control_tgz.getNextEntry()) != null) {
							log("control entry: " + control_entry.getName(), LogLevel.DEBUG.getLevel());
							if (control_entry.getName().trim().equals(CONTROL_FILE_NAME)) {
								ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
								IOUtils.copy(control_tgz, outputStream);
								String content_string = outputStream.toString("UTF-8");
								outputStream.close();
								controlHandler.setControlContent(content_string);
								log("control cont: " + outputStream.toString("utf-8"),LogLevel.DEBUG.getLevel());
								break;
							}
						}
						control_tgz.close();
						if (controlHandler.hasControlContent()) {
							controlHandler.handle(packageEntry);
						} else {
							throw new RuntimeException("no control content found for: "+ file.getName());
						}
						break;
					}
				}
				debStream.close();
				packages.addPackageEntry(packageEntry);
			} catch (Exception e) {
				String msg = FAILED_TO_CREATE_APT_REPO + " " + file.getName();
				log(msg, e, LogLevel.ERR.getLevel());
				throw new RuntimeException(msg, e);
			}
		}
		try {
			File packagesFile = new File(repoDir, PACKAGES_GZ);
			packagesWriter = new BufferedWriter(new OutputStreamWriter(
					new GZIPOutputStream(new FileOutputStream(packagesFile))));
			packagesWriter.write(packages.toString());
			DefaultHashes hashes = Utils.getDefaultDigests(packagesFile);
			ReleaseInfo pinfo = new ReleaseInfo(PACKAGES_GZ,
					packagesFile.length(), hashes);
			Release release = new Release();
			release.addInfo(pinfo);
			final File releaseFile = new File(repoDir, RELEASE);
			FileUtils.fileWrite(releaseFile, release.toString());
		} catch (IOException e) {
			throw new RuntimeException("writing files failed", e);
		} finally {
			if (packagesWriter != null) {
				try {
					packagesWriter.close();
				} catch (IOException e) {
					throw new RuntimeException("writing files failed", e);
				}
			}
		}
	}
}
