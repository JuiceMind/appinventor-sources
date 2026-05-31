// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2025 Juicemind, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.client;

import com.google.appinventor.client.editor.FileEditor;
import com.google.appinventor.client.editor.designer.DesignerEditor;
import com.google.appinventor.client.editor.simple.palette.AbstractPalettePanel;
import com.google.appinventor.client.editor.simple.palette.SimplePaletteItem;
import com.google.appinventor.client.editor.youngandroid.YaProjectEditor;
import com.google.appinventor.client.explorer.project.Project;
import com.google.appinventor.shared.rpc.component.ComponentImportResponse;
import com.google.appinventor.shared.rpc.project.ProjectNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidAssetsFolder;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidComponentsFolder;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidProjectNode;
import com.google.appinventor.shared.simple.ComponentDatabaseInterface;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.user.client.rpc.AsyncCallback;
import java.util.HashSet;
import java.util.Set;

/**
 * JSNI-exported helpers that the embed-mode bridge.js calls directly
 * (window.aiAddComponent, window.aiDeleteProject, window.aiCopyProject).
 *
 * These exist because the DOM-scraping / synthetic-keyboard paths the
 * bridge used to take were unreliable: GWT's compiled JS doesn't dispatch
 * synthetic Enter to non-focused palette items, and the project lifecycle
 * APIs live behind GWT-RPC which is hard to marshal from raw JS.
 *
 * install() is called once from Ode.onModuleLoad after the user is
 * authenticated. After install, the bridge can call:
 *
 *   window.aiAddComponent("Button")               -> boolean
 *   window.aiDeleteProject(projectId, onDone)     -> void
 *   window.aiCopyProject(projectId, newName, onDone) -> void
 *
 * Callbacks (onDone) receive (errorString|null, resultProjectId|null).
 */
public final class BridgeExports {
  private BridgeExports() {}

  /**
   * Installs window.aiAddComponent / aiDeleteProject / aiCopyProject.
   * Safe to call multiple times — subsequent calls overwrite.
   */
  public static native void install() /*-{
    $wnd.aiAddComponent = function (type) {
      return @com.google.appinventor.client.BridgeExports::addComponent(Ljava/lang/String;)(type);
    };
    $wnd.aiDeleteProject = function (projectId, onDone) {
      @com.google.appinventor.client.BridgeExports::deleteProject(Ljava/lang/String;Lcom/google/gwt/core/client/JavaScriptObject;)(String(projectId), onDone || null);
    };
    $wnd.aiCopyProject = function (projectId, newName, onDone) {
      @com.google.appinventor.client.BridgeExports::copyProject(Ljava/lang/String;Ljava/lang/String;Lcom/google/gwt/core/client/JavaScriptObject;)(String(projectId), String(newName), onDone || null);
    };
    $wnd.aiSetPaletteFilter = function (allowedTypesArray, allowExtensions) {
      var jsArr = allowedTypesArray || null;
      return @com.google.appinventor.client.BridgeExports::setPaletteFilter(Lcom/google/gwt/core/client/JsArrayString;Z)(jsArr, !!allowExtensions);
    };
    $wnd.aiClearPaletteFilter = function () {
      return @com.google.appinventor.client.BridgeExports::clearPaletteFilter()();
    };
    $wnd.aiImportExtension = function (uploadInfo, onDone) {
      @com.google.appinventor.client.BridgeExports::importExtension(Ljava/lang/String;Lcom/google/gwt/core/client/JavaScriptObject;)(String(uploadInfo), onDone || null);
    };
    $wnd.aiGetComponentInfo = function (type) {
      return @com.google.appinventor.client.BridgeExports::getComponentInfo(Ljava/lang/String;)(String(type));
    };
  }-*/;

  /**
   * Look up palette + documentation metadata for a single component type.
   * Returns a plain JS object with the fields the embedder needs to render
   * a help drawer / tooltip without round-tripping to the component DB.
   * Returns null if the type is unknown.
   */
  public static JavaScriptObject getComponentInfo(String componentType) {
    if (componentType == null || componentType.isEmpty()) return null;
    FileEditor fe = Ode.getInstance().getCurrentFileEditor();
    if (!(fe instanceof DesignerEditor)) return null;
    DesignerEditor<?, ?, ?, ?, ?> editor = (DesignerEditor<?, ?, ?, ?, ?>) fe;
    Object dbObj = editor.getComponentDatabase();
    if (!(dbObj instanceof ComponentDatabaseInterface)) return null;
    ComponentDatabaseInterface db = (ComponentDatabaseInterface) dbObj;
    try {
      String help = db.getHelpString(componentType);
      String helpUrl = db.getHelpUrl(componentType);
      String category = db.getCategoryString(componentType);
      String catDocUrl = db.getCategoryDocUrlString(componentType);
      String version = String.valueOf(db.getComponentVersion(componentType));
      String versionName = db.getComponentVersionName(componentType);
      boolean nonVisible = db.getNonVisible(componentType);
      boolean external = db.getComponentExternal(componentType);
      return buildInfoJs(componentType, help, helpUrl, category, catDocUrl,
                         version, versionName, nonVisible, external);
    } catch (Exception e) {
      Ode.CLog("BridgeExports.getComponentInfo failed for " + componentType + ": " + e.getMessage());
      return null;
    }
  }

  private static native JavaScriptObject buildInfoJs(
      String type, String help, String helpUrl, String category, String categoryDocUrl,
      String version, String versionName, boolean nonVisible, boolean external) /*-{
    return {
      type: type, help: help, helpUrl: helpUrl, category: category,
      categoryDocUrl: categoryDocUrl, version: version, versionName: versionName,
      nonVisible: nonVisible, external: external,
    };
  }-*/;

  /**
   * Two-step extension import:
   *   1. Embedder uploads the .aix to /ode/upload/component/<filename>
   *      via HTTP (multipart form, field name 'uploadComponentArchive')
   *      and receives an upload info string from the UploadResponse.
   *   2. Embedder calls this with the upload info; we ask ComponentService
   *      to actually install the component into the current project's
   *      assets folder.
   *
   * Why split: step 1 is a plain HTTP POST the bridge can do in JS via
   * fetch + FormData; step 2 is a GWT-RPC call that needs the typed
   * service stubs only available from Java.
   */
  public static void importExtension(String uploadInfo, final com.google.gwt.core.client.JavaScriptObject onDone) {
    if (uploadInfo == null || uploadInfo.isEmpty()) {
      invokeDone(onDone, "uploadInfo required (the response.info field from the upload step)", null);
      return;
    }
    final long projectId = Ode.getInstance().getCurrentYoungAndroidProjectId();
    if (projectId == 0) {
      invokeDone(onDone, "No project currently open", null);
      return;
    }
    Project project = Ode.getInstance().getProjectManager().getProject(projectId);
    if (project == null || !(project.getRootNode() instanceof YoungAndroidProjectNode)) {
      invokeDone(onDone, "Active project is not a Young Android project", null);
      return;
    }
    YoungAndroidAssetsFolder assets = ((YoungAndroidProjectNode) project.getRootNode()).getAssetsFolder();
    Ode.getInstance().getComponentService().importComponentToProject(
        uploadInfo, projectId, assets.getFileId(),
        new AsyncCallback<ComponentImportResponse>() {
          @Override public void onSuccess(ComponentImportResponse resp) {
            // ComponentImportResponse.Status: IMPORTED is the regular
            // success case; UPGRADED means we replaced an existing version.
            // Anything else (UNKNOWN_URL, FAILED, …) is a hard error.
            String status = resp == null ? "UNKNOWN" : String.valueOf(resp.getStatus());
            boolean ok = "IMPORTED".equals(status) || "UPGRADED".equals(status);
            if (!ok) {
              invokeDone(onDone, "Import returned status " + status, null);
              return;
            }
            // Server side stored the .aix; the project editor still needs
            // to learn about the new component nodes and register the
            // extension's palette items + block drawers. Mirrors what
            // ComponentImportWizard.ImportComponentCallback.onSuccess does.
            try {
              long destinationProjectId = resp.getProjectId();
              long currentProjectId = Ode.getInstance().getCurrentYoungAndroidProjectId();
              if (currentProjectId == destinationProjectId) {
                Project project = Ode.getInstance().getProjectManager().getProject(destinationProjectId);
                if (project != null && project.getRootNode() instanceof YoungAndroidProjectNode) {
                  YoungAndroidComponentsFolder componentsFolder =
                      ((YoungAndroidProjectNode) project.getRootNode()).getComponentsFolder();
                  Object pe = Ode.getInstance().getEditorManager().getOpenProjectEditor(destinationProjectId);
                  if (pe instanceof YaProjectEditor) {
                    YaProjectEditor projectEditor = (YaProjectEditor) pe;
                    for (ProjectNode node : resp.getNodes()) {
                      project.addNode(componentsFolder, node);
                      if ((node.getName().equals("component.json") ||
                           node.getName().equals("components.json"))
                          && countChar(node.getFileId(), '/') == 3) {
                        projectEditor.importExtension(node);
                      }
                    }
                  }
                }
              }
            } catch (Exception e) {
              // Best-effort: the .aix IS on disk; worst case reload to
              // see it in the palette.
              Ode.CLog("BridgeExports.importExtension post-register failed: " + e.getMessage());
            }
            invokeDone(onDone, null, status);
          }
          @Override public void onFailure(Throwable t) {
            invokeDone(onDone, t.getMessage(), null);
          }
        });
  }

  /**
   * Restrict the palette to only the listed component types. Pass null
   * or empty to show everything (use clearPaletteFilter to reset).
   * allowExtensions controls whether the Extensions category shows.
   * Returns true if the filter was applied, false if no designer is open.
   */
  public static boolean setPaletteFilter(JsArrayString allowedTypes, boolean allowExtensions) {
    AbstractPalettePanel<?, ?> abp = getActivePalette();
    if (abp == null) return false;
    final Set<String> allowed = new HashSet<String>();
    if (allowedTypes != null) {
      for (int i = 0; i < allowedTypes.length(); i++) {
        String t = allowedTypes.get(i);
        if (t != null && !t.isEmpty()) allowed.add(t);
      }
    }
    final boolean ext = allowExtensions;
    final boolean unrestricted = allowed.isEmpty();
    abp.setFilter(new AbstractPalettePanel.Filter() {
      @Override public boolean shouldShowComponent(String componentTypeName) {
        return unrestricted || allowed.contains(componentTypeName);
      }
      @Override public boolean shouldShowExtensions() {
        return ext;
      }
    }, false);
    return true;
  }

  /** Remove any custom filter — show every component again. */
  public static boolean clearPaletteFilter() {
    return setPaletteFilter(null, true);
  }

  private static AbstractPalettePanel<?, ?> getActivePalette() {
    FileEditor fe = Ode.getInstance().getCurrentFileEditor();
    if (!(fe instanceof DesignerEditor)) return null;
    DesignerEditor<?, ?, ?, ?, ?> editor = (DesignerEditor<?, ?, ?, ?, ?>) fe;
    Object panel = editor.getPalettePanel();
    return panel instanceof AbstractPalettePanel ? (AbstractPalettePanel<?, ?>) panel : null;
  }

  /** Returns true if the component was added, false on any failure. */
  public static boolean addComponent(String componentType) {
    if (componentType == null || componentType.isEmpty()) return false;
    FileEditor fe = Ode.getInstance().getCurrentFileEditor();
    if (!(fe instanceof DesignerEditor)) return false;
    DesignerEditor<?, ?, ?, ?, ?> editor = (DesignerEditor<?, ?, ?, ?, ?>) fe;
    Object panel = editor.getPalettePanel();
    if (panel == null) return false;
    // The palette panel is typed via generics; cast to the base abstract
    // class so we can call the public lookup method.
    com.google.appinventor.client.editor.simple.palette.AbstractPalettePanel<?, ?> abp =
        (com.google.appinventor.client.editor.simple.palette.AbstractPalettePanel<?, ?>) panel;
    SimplePaletteItem item = abp.getSimplePaletteItem(componentType);
    if (item == null) return false;
    try {
      item.addComponentToActiveForm();
      return true;
    } catch (Exception e) {
      Ode.CLog("BridgeExports.addComponent failed for " + componentType + ": " + e.getMessage());
      return false;
    }
  }

  /** Deletes a project. Async — callback fires with (errorOrNull, null). */
  public static void deleteProject(String projectIdStr, final com.google.gwt.core.client.JavaScriptObject onDone) {
    final long projectId;
    try {
      projectId = Long.parseLong(projectIdStr);
    } catch (NumberFormatException e) {
      invokeDone(onDone, "Invalid projectId: " + projectIdStr, null);
      return;
    }
    Ode.getInstance().getProjectService().deleteProject(projectId, new AsyncCallback<Void>() {
      @Override public void onSuccess(Void v) { invokeDone(onDone, null, null); }
      @Override public void onFailure(Throwable t) { invokeDone(onDone, t.getMessage(), null); }
    });
  }

  /** Duplicates a project under newName. Callback fires with (errorOrNull, newProjectIdString). */
  public static void copyProject(String projectIdStr, String newName, final com.google.gwt.core.client.JavaScriptObject onDone) {
    final long projectId;
    try {
      projectId = Long.parseLong(projectIdStr);
    } catch (NumberFormatException e) {
      invokeDone(onDone, "Invalid projectId: " + projectIdStr, null);
      return;
    }
    if (newName == null || newName.isEmpty()) {
      invokeDone(onDone, "newName is required", null);
      return;
    }
    Ode.getInstance().getProjectService().copyProject(projectId, newName,
        new AsyncCallback<com.google.appinventor.shared.rpc.project.UserProject>() {
      @Override
      public void onSuccess(com.google.appinventor.shared.rpc.project.UserProject p) {
        invokeDone(onDone, null, String.valueOf(p.getProjectId()));
      }
      @Override public void onFailure(Throwable t) { invokeDone(onDone, t.getMessage(), null); }
    });
  }

  private static int countChar(String s, char ch) {
    if (s == null) return 0;
    int n = 0;
    for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ch) n++;
    return n;
  }

  private static native void invokeDone(com.google.gwt.core.client.JavaScriptObject fn,
                                        String err, String result) /*-{
    if (fn) {
      try { fn(err, result); } catch (e) { }
    }
  }-*/;
}
