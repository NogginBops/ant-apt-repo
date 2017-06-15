/**
 * Copyright (c) 2015, technologist.kj@gmail.com, theo@m1theo.org.
 * 
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.codemarvels.ant.aptrepotask;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.security.GeneralSecurityException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.LogLevel;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.openpgp.PGPException;
import org.codehaus.plexus.util.FileUtils;

import com.codemarvels.ant.aptrepotask.packages.PackageEntry;
import com.codemarvels.ant.aptrepotask.packages.Packages;
import com.codemarvels.ant.aptrepotask.release.Release;
import com.codemarvels.ant.aptrepotask.release.ReleaseInfo;
import com.codemarvels.ant.aptrepotask.signing.PGPSigner;
import com.codemarvels.ant.aptrepotask.utils.ControlHandler;
import com.codemarvels.ant.aptrepotask.utils.DefaultHashes;
import com.codemarvels.ant.aptrepotask.utils.Utils;

public class AptRepoTask extends Task {
	private static final String RELEASE = "Release";
	private static final String RELEASEGPG = "Release.gpg";
	private static final String INRELEASE = "InRelease";
	private static final String PACKAGES = "Packages";
	private static final String PACKAGES_GZ = "Packages.gz";
	private static final String FAILED_TO_CREATE_APT_REPO = "Failed to create apt-repo: ";
	private static final String CONTROL_FILE_NAME = "control";
	private static final String FILE_DEB_EXT = ".deb";
	private BufferedWriter packagesWriter;

	/**
	 * Location of the apt repository.
	 */
	private String repoDir;

	private boolean sign = false;
	private File passphraseFile = null;
	private String passphrase = null;
	private File keyring = null;
	private String key = null;
	private String digest = "SHA256";

	public void setRepoDir(String repoDir) {
		this.repoDir = repoDir;
	}

	public void setSign(boolean sign) {
		this.sign = sign;
	}

	public void setPassphraseFile(File passphraseFile) {
		this.passphraseFile = passphraseFile;
	}

	public void setPassphrase(String passphrase) {
		this.passphrase = passphrase;
	}

	public void setKeyring(File keyring) {
		this.keyring = keyring;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public void setDigest(String digest) {
		this.digest = digest;
	}

	public String getRepoDir() {
		return repoDir;
	}

	public void execute() {
		String separator = System.getProperty("line.separator");
		System.setProperty("line.separator","\n");
		
		if (sign) {
			if (keyring == null || !keyring.exists()) {
				log("Signing requested, but no or invalid keyrring supplied", LogLevel.ERR.getLevel());
				throw new RuntimeException(FAILED_TO_CREATE_APT_REPO + "keyring invalid or missing");
			}
			if (key == null) {
				log("Signing requested, but no key supplied", LogLevel.ERR.getLevel());
				throw new RuntimeException(FAILED_TO_CREATE_APT_REPO + "key is missing");
			}
			if (passphrase == null && passphraseFile == null) {
				log("Signing requested, but no passphrase or passphrase file supplied", LogLevel.ERR.getLevel());
				throw new RuntimeException(
						FAILED_TO_CREATE_APT_REPO + "passphrase or passphrase file must be specified");
			}
			if (passphraseFile != null && !passphraseFile.exists()) {
				log("Signing requested, passphrase file does not exist: " + passphraseFile.getAbsolutePath(),
						LogLevel.ERR.getLevel());
				throw new RuntimeException(FAILED_TO_CREATE_APT_REPO + "passphrase file does not exist "
						+ passphraseFile.getAbsolutePath());
			}
		}
		if (repoDir == null) {
			log("repoDir attribute is empty !", LogLevel.ERR.getLevel());
			throw new RuntimeException("Bad attributes for apt-repo task");
		}
		log("repo dir: " + repoDir);
		File repoFolder = new File(repoDir);
		if (!repoFolder.exists()) {
			repoFolder.mkdirs();
		}
		File[] files = repoFolder.listFiles(new FileFilter() {
			@Override
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
			packageEntry.setSha512(Utils.getDigest("SHA-512", file));
			packageEntry.setMd5sum(Utils.getDigest("MD5", file));
			String fileName = file.getName();
			packageEntry.setFilename(fileName);
			log("found deb: " + fileName);
			try {
				ArchiveInputStream control_tgz;
				ArArchiveEntry entry;
				TarArchiveEntry control_entry;
				ArchiveInputStream debStream = new ArchiveStreamFactory().createArchiveInputStream("ar",
						new FileInputStream(file));
				while ((entry = (ArArchiveEntry) debStream.getNextEntry()) != null) {
					if (entry.getName().equals("control.tar.gz")) {
						ControlHandler controlHandler = new ControlHandler();
						GZIPInputStream gzipInputStream = new GZIPInputStream(debStream);
						control_tgz = new ArchiveStreamFactory().createArchiveInputStream("tar", gzipInputStream);
						while ((control_entry = (TarArchiveEntry) control_tgz.getNextEntry()) != null) {
							log("control entry: " + control_entry.getName());
							String name = control_entry.getName().trim();
							if (name.equals(CONTROL_FILE_NAME) || name.equals("./" + CONTROL_FILE_NAME)) {
								ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
								IOUtils.copy(control_tgz, outputStream);
								String content_string = outputStream.toString("UTF-8");
								outputStream.close();
								controlHandler.setControlContent(content_string);
								log("control cont: " + outputStream.toString("utf-8"), LogLevel.DEBUG.getLevel());
								break;
							}
						}
						control_tgz.close();
						if (controlHandler.hasControlContent()) {
							controlHandler.handle(packageEntry);
						} else {
							throw new RuntimeException("no control content found for: " + file.getName());
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
			Release release = new Release();

			File packagesFile = new File(repoDir, PACKAGES);
			BufferedWriter packagesWriter = new BufferedWriter(
					new OutputStreamWriter(new FileOutputStream(packagesFile)));
			packagesWriter.write(packages.toString());
			packagesWriter.close();
			DefaultHashes hashes = Utils.getDefaultDigests(packagesFile);
			ReleaseInfo pinfo = new ReleaseInfo(PACKAGES, packagesFile.length(), hashes);
			release.addInfo(pinfo);

			File packagesGzFile = new File(repoDir, PACKAGES_GZ);
			BufferedWriter packagesGzWriter = new BufferedWriter(
					new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(packagesGzFile))));
			packagesGzWriter.write(packages.toString());
			packagesGzWriter.close();
			DefaultHashes gzHashes = Utils.getDefaultDigests(packagesGzFile);
			ReleaseInfo gzPinfo = new ReleaseInfo(PACKAGES_GZ, packagesGzFile.length(), gzHashes);
			release.addInfo(gzPinfo);

			final File releaseFile = new File(repoDir, RELEASE);
			FileUtils.fileWrite(releaseFile, release.toString());

			if (sign) {
				if (passphraseFile != null) {
					log("passphrase file will be used " + passphraseFile.getAbsolutePath(), LogLevel.DEBUG.getLevel());
					BufferedReader pwReader = new BufferedReader(new FileReader(passphraseFile));
					passphrase = pwReader.readLine();
					pwReader.close();
				}
				final File inReleaseFile = new File(repoDir, INRELEASE);
				final File releaseGpgFile = new File(repoDir, RELEASEGPG);
				PGPSigner signer = new PGPSigner(new FileInputStream(keyring), key, passphrase, getDigestCode(digest));
				signer.clearSignDetached(release.toString(), new FileOutputStream(releaseGpgFile));
				signer.clearSign(release.toString(), new FileOutputStream(inReleaseFile));
			}
		} catch (PGPException e) {
			throw new RuntimeException("gpg signing failed", e);
		} catch (IOException e) {
			throw new RuntimeException("writing files failed", e);
		} catch (GeneralSecurityException e) {
			throw new RuntimeException("generating release failed", e);
		} finally {
			if (packagesWriter != null) {
				try {
					packagesWriter.close();
				} catch (IOException e) {
					throw new RuntimeException("writing files failed", e);
				}
			}
		}
		
		System.setProperty("line.separator", separator);
	}

	static int getDigestCode(String digestName) {
		if ("SHA1".equals(digestName)) {
			return HashAlgorithmTags.SHA1;
		} else if ("MD2".equals(digestName)) {
			return HashAlgorithmTags.MD2;
		} else if ("MD5".equals(digestName)) {
			return HashAlgorithmTags.MD5;
		} else if ("RIPEMD160".equals(digestName)) {
			return HashAlgorithmTags.RIPEMD160;
		} else if ("SHA256".equals(digestName)) {
			return HashAlgorithmTags.SHA256;
		} else if ("SHA384".equals(digestName)) {
			return HashAlgorithmTags.SHA384;
		} else if ("SHA512".equals(digestName)) {
			return HashAlgorithmTags.SHA512;
		} else if ("SHA224".equals(digestName)) {
			return HashAlgorithmTags.SHA224;
		} else {
			throw new RuntimeException("unknown hash algorithm tag in digestName: " + digestName);
		}
	}
}
