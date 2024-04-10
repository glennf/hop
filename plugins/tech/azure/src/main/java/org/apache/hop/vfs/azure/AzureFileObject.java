/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.hop.vfs.azure;

import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.*;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.provider.AbstractFileName;
import org.apache.commons.vfs2.provider.AbstractFileObject;
import org.apache.commons.vfs2.provider.UriParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AzureFileObject extends AbstractFileObject<AzureFileSystem> {

  public class AppendBlobOutputStream extends OutputStream {

    private CloudAppendBlob ab;
    private final OutputStream outputStream;
    long written = 0;

    public AppendBlobOutputStream(CloudAppendBlob ab, OutputStream outputStream) {
      this.ab = ab;
      this.outputStream = outputStream;
    }

    @Override
    public void write(int b) throws IOException {
      outputStream.write(b);
      written(1);
    }

    @Override
    public void write(byte[] b) throws IOException {
      write(b, 0, b.length);
    }

    protected void written(int len) {
      written += len;
      lastModified = System.currentTimeMillis();
      size = written;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      outputStream.write(b, off, len);
      written(len);
    }

    @Override
    public void flush() throws IOException {
      super.flush();
    }

    @Override
    public void close() throws IOException {

      HashMap<String, String> md = new HashMap<>(ab.getMetadata());
      md.put("ActualLength", String.valueOf(written));
      ab.getProperties().setContentDisposition("vfs ; length=\"" + written + "\"");
      ab.setMetadata(md);
      try {
        ab.uploadProperties();
      } catch (StorageException e) {
        throw new IOException("Failed to update meta-data.", e);
      }

      outputStream.close();
      closeBlob();
    }
  }

  private final CloudBlobClient service;
  private boolean attached = false;
  private long size;
  private long lastModified;
  private FileType type;
  private List<String> children = null;
  private CloudBlobContainer container;
  private String containerPath;
  private CloudBlob cloudBlob;
  private CloudBlobDirectory cloudDir;
  private String markerFileName = ".cvfs.temp";

  public AzureFileObject(
      AbstractFileName fileName, AzureFileSystem fileSystem, CloudBlobClient service)
      throws FileSystemException {
    super(fileName, fileSystem);
    this.service = service;
  }

  @Override
  protected void doSetAttribute(String attrName, Object value) throws Exception {
    if (container != null && !containerPath.equals("")) {
      if (cloudBlob != null) {
        if (attrName.equals("cacheControl"))
          cloudBlob.getProperties().setCacheControl(value.toString());
        else if (attrName.equals("contentType"))
          cloudBlob.getProperties().setContentType(value.toString());
        else if (attrName.equals("contentMD5"))
          cloudBlob.getProperties().setContentMD5(value.toString());
        else if (attrName.equals("contentLanguage"))
          cloudBlob.getProperties().setContentLanguage(value.toString());
        else if (attrName.equals("contentDisposition"))
          cloudBlob.getProperties().setContentDisposition(value.toString());
        else if (attrName.equals("contentEncoding"))
          cloudBlob.getProperties().setContentEncoding(value.toString());
        else cloudBlob.getMetadata().put(attrName, value.toString());
      }
    } else {
      throw new FileSystemException("Setting of attributes not supported on this file.");
    }
  }

  @Override
  protected Map<String, Object> doGetAttributes() throws Exception {
    Map<String, Object> attrs = new HashMap<>();
    if (container != null && !containerPath.equals("")) {
      if (cloudBlob != null) {
        attrs.put("cacheControl", cloudBlob.getProperties().getCacheControl());
        attrs.put("blobType", cloudBlob.getProperties().getBlobType());
        attrs.put("contentDisposition", cloudBlob.getProperties().getContentDisposition());
        attrs.put("contentEncoding", cloudBlob.getProperties().getContentEncoding());
        attrs.put("contentLanguage", cloudBlob.getProperties().getContentLanguage());
        attrs.put("contentType", cloudBlob.getProperties().getContentType());
        attrs.put("copyState", cloudBlob.getProperties().getCopyState());
        attrs.put("etag", cloudBlob.getProperties().getEtag());
        attrs.put("leaseDuration", cloudBlob.getProperties().getLeaseDuration());
        attrs.put("leaseState", cloudBlob.getProperties().getLeaseState());
        attrs.put("leaseStatus", cloudBlob.getProperties().getLeaseStatus());
        attrs.put("pageBlobSequenceNumber", cloudBlob.getProperties().getPageBlobSequenceNumber());
        attrs.putAll(cloudBlob.getMetadata());
      }
    }
    return attrs;
  }

  @Override
  protected void doRemoveAttribute(String attrName) throws Exception {
    if (container != null && !containerPath.equals("") && cloudBlob != null) {
      if (Arrays.asList(
              "cacheControl",
              "blobType",
              "contentDisposition",
              "contentEncoding",
              "contentLanguage",
              "contentType",
              "copyState",
              "etag",
              "leaseDuration",
              "leaseState",
              "leaseStatus",
              "pageBlobSequenceNumber")
          .contains(attrName)) {
        throw new FileSystemException("Removal of this attributes not supported on this file.");
      }
      cloudBlob.getMetadata().remove(attrName);
    } else {
      throw new FileSystemException("Removal of attributes not supported on this file.");
    }
  }

  @Override
  protected void doAttach() throws URISyntaxException, StorageException {
    if (!attached) {
      if (getName().getPath().equals("/")) {
        children = new ArrayList<>();
        for (CloudBlobContainer container : service.listContainers()) {
          children.add(container.getName());
        }
        size = children.size();
        lastModified = 0;
        type = FileType.FOLDER;
        container = null;
        containerPath = "";
      } else {
        String containerName = ((AzureFileName) getName()).getContainer();
        container = service.getContainerReference(containerName);
        containerPath = ((AzureFileName) getName()).getPathAfterContainer();
        String thisPath = "/" + containerName + containerPath;
        if (container.exists()) {
          children = new ArrayList<>();
          if (containerPath.equals("")) {
            if (container.exists()) {
              for (ListBlobItem item : container.listBlobs()) {
                StringBuilder path = new StringBuilder(item.getUri().getPath());
                UriParser.extractFirstElement(path);
                children.add(path.toString().substring(1));
              }
              type = FileType.FOLDER;
            } else {
              type = FileType.IMAGINARY;
            }
            lastModified = 0;
            size = children.size();
          } else {
            /*
             * Look in the parent path for this filename AND and
             * direct descendents
             */
            cloudBlob = null;
            cloudDir = null;
            String relpath =
                removeLeadingSlash(
                    ((AzureFileName) (getName().getParent())).getPathAfterContainer());
            for (ListBlobItem item :
                relpath.equals("") ? container.listBlobs() : container.listBlobs(relpath + "/")) {
              String itemPath = removeTrailingSlash(item.getUri().getPath());
              if (pathsMatch(itemPath, thisPath)) {
                if (item instanceof CloudBlob) {
                  cloudBlob = (CloudBlob) item;
                } else {
                  cloudDir = (CloudBlobDirectory) item;
                  for (ListBlobItem blob : cloudDir.listBlobs()) {
                    URI blobUri = blob.getUri();
                    String path = blobUri.getPath();
                    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                    int idx = path.lastIndexOf('/');
                    if (idx != -1) path = path.substring(idx + 1);
                    children.add(path);
                  }
                }
                break;
              }
            }
            if (cloudBlob != null) {
              type = FileType.FILE;
              size = cloudBlob.getProperties().getLength();
              if (cloudBlob.getMetadata().containsKey("ActualLength")) {
                size = Long.parseLong(cloudBlob.getMetadata().get("ActualLength"));
              }
              String disp = cloudBlob.getProperties().getContentDisposition();
              if (disp != null && disp.startsWith("vfs ; length=\"")) {
                size = Long.parseLong(disp.substring(14, disp.length() - 1));
              }
              Date lastModified2 = cloudBlob.getProperties().getLastModified();
              lastModified = lastModified2 == null ? 0 : lastModified2.getTime();
            } else if (cloudDir != null) {
              type = FileType.FOLDER;
              size = children.size();
              lastModified = 0;
            } else {
              lastModified = 0;
              type = FileType.IMAGINARY;
              size = 0;
            }
          }
        } else {
          lastModified = 0;
          type = FileType.IMAGINARY;
          size = 0;
          cloudBlob = null;
          cloudDir = null;
        }
      }
    }
  }

    private boolean pathsMatch(String path, String currentPath) {
        return path.replace("/" + ((AzureFileSystem) getFileSystem()).getAccount(), "").equals(currentPath);
    }

    @Override
  protected void doDetach() throws Exception {
    if (this.attached) {
      this.attached = false;
      this.children = null;
      this.size = 0;
      this.type = null;
      this.container = null;
      this.containerPath = null;
      this.cloudBlob = null;
      this.cloudDir = null;
    }
  }

  @Override
  protected void onChange() throws IOException {
    this.refresh();
  }

  @Override
  protected boolean doIsHidden() throws Exception {
    return getName().getBaseName().equals(markerFileName);
  }

  @Override
  protected void doDelete() throws Exception {
    if (container == null) {
      throw new UnsupportedOperationException();
    } else {
      FileObject parent = getParent();
      boolean lastFile = parent.getChildren().length == 1;
      try {
        if (containerPath.equals("")) {
          container.delete();
        } else {
          if (cloudBlob != null) cloudBlob.delete();
          else if (cloudDir != null) {
            for (ListBlobItem item :
                container.listBlobs(((AzureFileName) getName()).getPathAfterContainer(), true)) {
              String path = item.getUri().getPath();
              if (item instanceof CloudBlob && path.startsWith(getName().getPath())) {
                ((CloudBlob) item).delete();
              }
            }
          } else {
            throw new UnsupportedOperationException();
          }
          // If this was the last file in the create, we create a new
          // marker file to keep the directory open
          if (lastFile) {
            FileObject marker = parent.resolveFile(markerFileName);
            marker.createFile();
          }
        }
      } finally {
        type = FileType.IMAGINARY;
        children = null;
        size = 0;
        lastModified = 0;
      }
    }
  }

  @Override
  protected void doRename(FileObject newfile) throws Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  protected void doCreateFolder() throws StorageException, URISyntaxException, IOException {
    if (container == null) {
      throw new UnsupportedOperationException();
    } else if (containerPath.equals("")) {
      container.create();
      type = FileType.FOLDER;
      children = new ArrayList<>();
    } else {
      /*
       * Azure doesn't actually have folders, so we create a temporary
       * 'file' in the 'folder'
       */
      CloudBlockBlob blob =
          container.getBlockBlobReference(containerPath.substring(1) + "/" + markerFileName);
      byte[] buf =
          ("This is a temporary blob created by a Commons VFS application to simulate a folder. It "
                  + "may be safely deleted, but this will hide the folder in the application if it is empty.")
              .getBytes("UTF-8");
      blob.uploadFromByteArray(buf, 0, buf.length);
      type = FileType.FOLDER;
      children = new ArrayList<>();
    }
  }

  @Override
  protected long doGetLastModifiedTime() throws Exception {
    return lastModified;
  }

  @Override
  protected OutputStream doGetOutputStream(boolean bAppend) throws Exception {
    if (container != null && !containerPath.equals("")) {
      if (bAppend) throw new UnsupportedOperationException();
      final CloudAppendBlob cab = container.getAppendBlobReference(removeLeadingSlash(containerPath));

      if (type == FileType.IMAGINARY) {
        type = FileType.FILE;
        return new AppendBlobOutputStream(
            cab, cab.openWriteNew());
      } else {
        return new AppendBlobOutputStream(
            cab, cab.openWriteExisting());
      }
    } else {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  protected InputStream doGetInputStream() throws Exception {
    if (container != null && !containerPath.equals("") && type == FileType.FILE) {
      return new AppendBlobInputStream(cloudBlob.openInputStream(), size);
    } else {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  protected FileType doGetType() throws Exception {
    return type;
  }

  @Override
  protected String[] doListChildren() throws Exception {
    return children == null ? null : children.toArray(new String[0]);
  }

  @Override
  protected long doGetContentSize() throws Exception {
    return size;
  }

  private void closeBlob() {
    String pathAfterContainer =
        removeLeadingSlash(((AzureFileName) getName().getParent()).getPathAfterContainer()) + "/";
    for (ListBlobItem item : container.listBlobs(pathAfterContainer)) {
      String itemPath = item.getUri().getPath();
      itemPath = removeTrailingSlash(itemPath);
      if (itemPath.equals(getName().getPath())) {
        if (item instanceof CloudBlob) {
          cloudBlob = (CloudBlob) item;
        }
      }
    }
  }

  private static String removeTrailingSlash(String itemPath) {
    while (itemPath.endsWith("/")) itemPath = itemPath.substring(0, itemPath.length() - 1);
    return itemPath;
  }

  private static String removeLeadingSlash(String relpath) {
    while (relpath.startsWith("/")) relpath = relpath.substring(1);
    return relpath;
  }
}
