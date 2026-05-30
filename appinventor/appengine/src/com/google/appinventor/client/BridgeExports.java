// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2025 Juicemind, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.client;

import com.google.appinventor.client.editor.FileEditor;
import com.google.appinventor.client.editor.designer.DesignerEditor;
import com.google.appinventor.client.editor.simple.palette.SimplePaletteItem;
import com.google.gwt.user.client.rpc.AsyncCallback;

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
  }-*/;

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

  private static native void invokeDone(com.google.gwt.core.client.JavaScriptObject fn,
                                        String err, String result) /*-{
    if (fn) {
      try { fn(err, result); } catch (e) { }
    }
  }-*/;
}
