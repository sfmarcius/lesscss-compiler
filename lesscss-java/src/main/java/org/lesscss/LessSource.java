/* Copyright 2011-2012 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lesscss;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lesscss.logging.LessLogger;
import org.lesscss.logging.LessLoggerFactory;

import static java.util.regex.Pattern.MULTILINE;

/**
 * Represents the metadata and content of a LESS source.
 *
 * @author Marcel Overdijk
 */
public class LessSource {
    
    private static final LessLogger LOGGER = LessLoggerFactory.getLogger(LessSource.class);
    /**
     * The <code>Pattern</code> used to match imported files.
     * <p/>
     * Updated to match import-once.
     * <p/>
     * Note that <code>@import-once</code> was introduced on lesscss v1.3.1, to avoid the same import being
     * processed multiple times om more complexes import-trees.
     * <p/>
     * From lesscss v1.4.0-beta-1, <code>@import-once</code> has been removed and the <code>@import</code> now the <code>@import-once</code> behaviour.
     */
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^(?!\\s*//\\s*)@import(\\-once)?\\s+(url\\()?\\s*(\"|')(.+)\\s*(\"|')(\\))?\\s*;.*$", MULTILINE);

    private File file;
    private String content;
    private String normalizedContent;
    private Map<String, LessSource> imports = new LinkedHashMap<String, LessSource>();

    /**
     * Constructs a new <code>LessSource</code>.
     * <p>
     * This will read the metadata and content of the LESS source, and will automatically resolve the imports.
     * </p>
     * <p>
     * The file is read using the default Charset of the platform
     * </p>
     *
     * @param file The <code>File</code> reference to the LESS source to read.
     * @throws FileNotFoundException If the LESS source (or one of its imports) could not be found.
     * @throws IOException           If the LESS source cannot be read.
     */
    public LessSource(File file) throws FileNotFoundException, IOException {
        this(file, Charset.defaultCharset());
    }

    /**
     * Constructs a new <code>LessSource</code>.
     * <p>
     * This will read the metadata and content of the LESS source, and will automatically resolve the imports.
     * </p>
     *
     * @param file    The <code>File</code> reference to the LESS source to read.
     * @param charset Charset used to read the less file.
     * @throws FileNotFoundException If the LESS source (or one of its imports) could not be found.
     * @throws IOException           If the LESS source cannot be read.
     */
    public LessSource(File file, Charset charset) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("File must not be null.");
        }
        if (!file.exists()) {
            throw new FileNotFoundException("File " + file.getAbsolutePath() + " not found.");
        }
        this.file = file;
        this.content = FileUtils.readFileToString(file, charset);
        this.imports = new LinkedHashMap<String, LessSource>(10);
        this.normalizedContent = resolveImports(this.content, this.file, this.imports);
    }

    /**
     * Returns the absolute pathname of the LESS source.
     *
     * @return The absolute pathname of the LESS source.
     */
    public String getAbsolutePath() {
        return file.getAbsolutePath();
    }

    /**
     * Returns the content of the LESS source.
     *
     * @return The content of the LESS source.
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns the normalized content of the LESS source.
     * <p>
     * The normalized content represents the LESS source as a flattened source
     * where import statements have been resolved and replaced by the actual
     * content.
     * </p>
     *
     * @return The normalized content of the LESS source.
     */
    public String getNormalizedContent() {
        return normalizedContent;
    }

    /**
     * Returns the time that the LESS source was last modified.
     *
     * @return A <code>long</code> value representing the time the file was last modified, measured in milliseconds since the epoch (00:00:00 GMT, January 1, 1970).
     */
    public long getLastModified() {
        return file.lastModified();
    }

    /**
     * Returns the time that the LESS source, or one of its imports, was last modified.
     *
     * @return A <code>long</code> value representing the time the file was last modified, measured in milliseconds since the epoch (00:00:00 GMT, January 1, 1970).
     */
    public long getLastModifiedIncludingImports() {
        long lastModified = getLastModified();
        for (Map.Entry<String, LessSource> entry : imports.entrySet()) {
            LessSource importedLessSource = entry.getValue();
            long importedLessSourceLastModified = importedLessSource.getLastModifiedIncludingImports();
            if (importedLessSourceLastModified > lastModified) {
                lastModified = importedLessSourceLastModified;
            }
        }
        return lastModified;
    }

    /**
     * Returns the LESS sources imported by this LESS source.
     * <p>
     * The returned imports are represented by a
     * <code>Map&lt;String, LessSource&gt;</code> which contains the filename and the
     * <code>LessSource</code>.
     * </p>
     *
     * @return The LESS sources imported by this LESS source.
     */
    public Map<String, LessSource> getImports() {
        return imports;
    }

    /**
     * Resolve imports without letting the same file being imported multiple times (@import-once behaviour on imports).
     *
     * @param rawSource     The sourcecode to have its imports expanded. imports already expanded will be ignored.
     * @param sharedImports The shared map of imports, where the root sourcecode and its dependencies will look for if some import was expanded already.
     * @return The normalized code.
     * @throws FileNotFoundException If some import could not be found.
     * @throws IOException           If some import could not be loaded.
     */
    private static String resolveImports(final String rawSource, final File file, final Map<String, LessSource> sharedImports) throws FileNotFoundException, IOException {
        String ret = rawSource;
        Matcher importMatcher = IMPORT_PATTERN.matcher(ret);
        while (importMatcher.find()) {
            String importedFileName = importMatcher.group(4); // points to the (.+) group on the regex
            importedFileName = importedFileName.matches(".*\\.(le?|c)ss$") ? importedFileName : importedFileName + ".less";
            boolean css = importedFileName.matches(".*css$");
            if (!css) {
                if (!sharedImports.containsKey(importedFileName)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(importedFileName + " imported");
                    }
                    final File importedFile = new File(file.getParentFile(), importedFileName);
                    final LessSource importedLessSource = new LessSource(importedFile);
                    sharedImports.put(importedFileName, importedLessSource);
                    ret = ret.substring(0, importMatcher.start())
                          + resolveImports(importedLessSource.getContent(), importedFile, sharedImports)
                          + ret.substring(importMatcher.end());
                } else {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(importedFileName + " already imported. ignoring...");
                    }
                    ret = ret.substring(0, importMatcher.start()) + ret.substring(importMatcher.end());
                }
                importMatcher = IMPORT_PATTERN.matcher(ret);
            }
        }
        return ret;
    }
}
