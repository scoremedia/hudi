/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.common.table.log;

import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieLogFile;
import org.apache.hudi.common.table.log.block.HoodieLogBlock;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.ReflectionUtils;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.storage.HoodieStorage;
import org.apache.hudi.storage.StoragePath;

import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * File Format for Hoodie Log Files. The File Format consists of blocks each separated with a MAGIC sync marker. A Block
 * can either be a Data block, Command block or Delete Block. Data Block - Contains log records serialized as Avro
 * Binary Format Command Block - Specific commands like ROLLBACK_PREVIOUS-BLOCK - Tombstone for the previously written
 * block Delete Block - List of keys to delete - tombstone for keys
 */
public interface HoodieLogFormat {

  /**
   * Magic 6 bytes we put at the start of every block in the log file.
   */
  byte[] MAGIC = new byte[] {'#', 'H', 'U', 'D', 'I', '#'};

  /**
   * The current version of the log format. Anytime the log format changes this version needs to be bumped and
   * corresponding changes need to be made to {@link HoodieLogFormatVersion}
   */
  int CURRENT_VERSION = 1;

  String UNKNOWN_WRITE_TOKEN = "1-0-1";

  String DEFAULT_WRITE_TOKEN = "0-0-0";

  String DEFAULT_LOG_FORMAT_WRITER = "org.apache.hudi.common.table.log.HoodieLogFormatWriter";

  /**
   * Writer interface to allow appending block to this file format.
   */
  interface Writer extends Closeable {

    /**
     * @return the path to the current {@link HoodieLogFile} being written to.
     */
    HoodieLogFile getLogFile();

    /**
     * Append Block to a log file.
     * @return {@link AppendResult} containing result of the append.
     */
    AppendResult appendBlock(HoodieLogBlock block) throws IOException, InterruptedException;

    /**
     * Appends the list of blocks to a logfile.
     * @return {@link AppendResult} containing result of the append.
     */
    AppendResult appendBlocks(List<HoodieLogBlock> blocks) throws IOException, InterruptedException;

    long getCurrentSize() throws IOException;
  }

  /**
   * Reader interface which is an Iterator of HoodieLogBlock.
   */
  interface Reader extends Closeable, Iterator<HoodieLogBlock> {

    /**
     * @return the path to this {@link HoodieLogFormat}
     */
    HoodieLogFile getLogFile();

    /**
     * Read log file in reverse order and check if prev block is present.
     * 
     * @return
     */
    boolean hasPrev();

    /**
     * Read log file in reverse order and return prev block if present.
     * 
     * @return
     * @throws IOException
     */
    HoodieLogBlock prev() throws IOException;
  }

  /**
   * Builder class to construct the default log format writer.
   */
  class WriterBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(WriterBuilder.class);
    // Default max log file size 512 MB
    public static final long DEFAULT_SIZE_THRESHOLD = 512 * 1024 * 1024L;

    // Buffer size
    private Integer bufferSize;
    // Replication for the log file
    private Short replication;
    // FileSystem
    private HoodieStorage storage;
    // Size threshold for the log file. Useful when used with a rolling log appender
    private Long sizeThreshold;
    // Log File extension. Could be .avro.delta or .avro.commits etc
    private String fileExtension;
    // File Id
    private String logFileId;
    // File Commit Time stamp
    private String instantTime;
    // version number for this log file. If not specified, then the current version will be
    // computed by inspecting the file system
    private Integer logVersion;
    // file len of this log file
    private Long fileLen = 0L;
    // Location of the directory containing the log
    private StoragePath parentPath;
    // Log File Write Token
    private String logWriteToken;
    // optional file suffix
    private String suffix;
    // Rollover Log file write token
    private String rolloverLogWriteToken;
    // A call back triggered with log file operation
    private HoodieLogFileWriteCallback logFileWriteCallback;

    public WriterBuilder withBufferSize(int bufferSize) {
      this.bufferSize = bufferSize;
      return this;
    }

    public WriterBuilder withReplication(short replication) {
      this.replication = replication;
      return this;
    }

    public WriterBuilder withRolloverLogWriteToken(String rolloverLogWriteToken) {
      this.rolloverLogWriteToken = rolloverLogWriteToken;
      return this;
    }

    public WriterBuilder withLogWriteToken(String logWriteToken) {
      this.logWriteToken = logWriteToken;
      return this;
    }

    public WriterBuilder withSuffix(String suffix) {
      this.suffix = suffix;
      return this;
    }

    public WriterBuilder withStorage(HoodieStorage storage) {
      this.storage = storage;
      return this;
    }

    public WriterBuilder withSizeThreshold(long sizeThreshold) {
      this.sizeThreshold = sizeThreshold;
      return this;
    }

    public WriterBuilder withFileExtension(String logFileExtension) {
      this.fileExtension = logFileExtension;
      return this;
    }

    public WriterBuilder withFileId(String fileId) {
      this.logFileId = fileId;
      return this;
    }

    public WriterBuilder overBaseCommit(String baseCommit) {
      this.instantTime = baseCommit;
      return this;
    }

    public WriterBuilder withLogVersion(int version) {
      this.logVersion = version;
      return this;
    }

    public WriterBuilder withLogWriteCallback(HoodieLogFileWriteCallback logFileWriteCallback) {
      this.logFileWriteCallback = logFileWriteCallback;
      return this;
    }

    public WriterBuilder withFileSize(long fileLen) {
      this.fileLen = fileLen;
      return this;
    }

    public WriterBuilder onParentPath(StoragePath parentPath) {
      this.parentPath = parentPath;
      return this;
    }

    public Writer build() throws IOException {
      LOG.info("Building HoodieLogFormat Writer");
      if (storage == null) {
        throw new IllegalArgumentException("fs is not specified");
      }
      if (logFileId == null) {
        throw new IllegalArgumentException("FileID is not specified");
      }
      if (instantTime == null) {
        throw new IllegalArgumentException("BaseCommitTime is not specified");
      }
      if (fileExtension == null) {
        throw new IllegalArgumentException("File extension is not specified");
      }
      if (parentPath == null) {
        throw new IllegalArgumentException("Log file parent location is not specified");
      }

      if (rolloverLogWriteToken == null) {
        rolloverLogWriteToken = UNKNOWN_WRITE_TOKEN;
      }

      if (logFileWriteCallback == null) {
        // use a callback do nothing here as default callback.
        logFileWriteCallback = new HoodieLogFileWriteCallback() {};
      }

      if (logVersion == null) {
        LOG.info("Computing the next log version for " + logFileId + " in " + parentPath);
        Option<Pair<Integer, String>> versionAndWriteToken =
            FSUtils.getLatestLogVersion(storage, parentPath, logFileId, fileExtension, instantTime);
        if (versionAndWriteToken.isPresent()) {
          logVersion = versionAndWriteToken.get().getKey();
          logWriteToken = versionAndWriteToken.get().getValue();
        } else {
          logVersion = HoodieLogFile.LOGFILE_BASE_VERSION;
          // this is the case where there is no existing log-file.
          // Use rollover write token as write token to create new log file with tokens
          logWriteToken = rolloverLogWriteToken;
        }
        LOG.info("Computed the next log version for {} in {} as {} with write-token {}", logFileId, parentPath, logVersion, logWriteToken);
      }

      if (logWriteToken == null) {
        // This is the case where we have existing log-file with old format. rollover to avoid any conflicts
        logVersion += 1;
        fileLen = 0L;
        logWriteToken = rolloverLogWriteToken;
      }

      if (suffix != null) {
        // A little hacky to simplify the file name concatenation:
        // patch the write token with an optional suffix
        // instead of adding a new extension
        logWriteToken = logWriteToken + suffix;
        rolloverLogWriteToken = rolloverLogWriteToken + suffix;
      }

      StoragePath logPath = new StoragePath(parentPath,
          FSUtils.makeLogFileName(logFileId, fileExtension, instantTime, logVersion, logWriteToken));
      LOG.info("HoodieLogFile on path {}", logPath);
      HoodieLogFile logFile = new HoodieLogFile(logPath, fileLen);

      if (sizeThreshold == null) {
        sizeThreshold = DEFAULT_SIZE_THRESHOLD;
      }
      return (Writer) ReflectionUtils.loadClass(
          DEFAULT_LOG_FORMAT_WRITER,
          new Class[] {HoodieStorage.class, HoodieLogFile.class, Integer.class, Short.class, Long.class, String.class, HoodieLogFileWriteCallback.class},
          storage, logFile, bufferSize, replication, sizeThreshold,
          rolloverLogWriteToken, logFileWriteCallback);
    }
  }

  static WriterBuilder newWriterBuilder() {
    return new WriterBuilder();
  }

  static HoodieLogFormat.Reader newReader(HoodieStorage storage, HoodieLogFile logFile, Schema readerSchema)
      throws IOException {
    return new HoodieLogFileReader(storage, logFile, readerSchema, HoodieLogFileReader.DEFAULT_BUFFER_SIZE);
  }

  static HoodieLogFormat.Reader newReader(HoodieStorage storage, HoodieLogFile logFile, Schema readerSchema, boolean reverseReader) throws IOException {
    return new HoodieLogFileReader(storage, logFile, readerSchema, HoodieLogFileReader.DEFAULT_BUFFER_SIZE, reverseReader);
  }

  /**
   * A set of feature flags associated with a log format. Versions are changed when the log format changes. TODO(na) -
   * Implement policies around major/minor versions
   */
  abstract class LogFormatVersion {

    private final int version;

    LogFormatVersion(int version) {
      this.version = version;
    }

    public int getVersion() {
      return version;
    }

    public abstract boolean hasMagicHeader();

    public abstract boolean hasContent();

    public abstract boolean hasContentLength();

    public abstract boolean hasOrdinal();

    public abstract boolean hasHeader();

    public abstract boolean hasFooter();

    public abstract boolean hasLogBlockLength();
  }
}
