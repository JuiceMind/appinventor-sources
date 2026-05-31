// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2026 JuiceMind, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0
//
// Bridge for designer-side component operations. Mirrors the AssetManager
// JSNI-export pattern: a singleton whose constructor calls
// exportMethodsToJavascript() to expose the operations to the parent React
// app's postMessage bridge (see server/bridge/bridge.js).
//
// All public methods locate the component by name via the *current* file
// editor (which must be a DesignerEditor, e.g. YaFormEditor). Operations on
// the wrong editor type, missing components, or invalid arguments return
// false rather than throwing — the JS side relies on the boolean return for
// structured error reporting.

package com.google.appinventor.client;

import com.google.appinventor.client.editor.FileEditor;
import com.google.appinventor.client.editor.blocks.BlocksEditor;
import com.google.appinventor.client.editor.designer.DesignerEditor;
import com.google.appinventor.client.editor.simple.SimpleComponentDatabase;
import com.google.appinventor.client.editor.simple.components.MockComponent;
import com.google.appinventor.client.explorer.project.Project;
import com.google.appinventor.client.youngandroid.TextValidators;
import com.google.appinventor.shared.properties.json.JSONUtil;
import com.google.appinventor.shared.rpc.project.ProjectNode;
import com.google.appinventor.shared.rpc.project.ProjectRootNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidAssetNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidAssetsFolder;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidBlocksNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidProjectNode;

/**
 * JSNI bridge that exposes designer component operations
 * ({@code delete}/{@code rename}/{@code setProperty}) to JavaScript.
 *
 * <p>The exported globals are:
 * <ul>
 *   <li>{@code window.ComponentEditor_deleteComponent(name)} → boolean</li>
 *   <li>{@code window.ComponentEditor_renameComponent(oldName, newName)} → boolean</li>
 *   <li>{@code window.ComponentEditor_setComponentProperty(name, prop, value)} → boolean</li>
 * </ul>
 */
public final class ComponentEditor {

  private static ComponentEditor INSTANCE;

  private ComponentEditor() {
    exportMethodsToJavascript();
  }

  /** Lazy singleton — calling this triggers the JSNI export. */
  public static ComponentEditor getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new ComponentEditor();
    }
    return INSTANCE;
  }

  // ─── Lookup helper ────────────────────────────────────────────────────

  private static MockComponent findComponent(String componentName) {
    if (componentName == null || componentName.isEmpty()) {
      return null;
    }
    FileEditor editor = Ode.getInstance().getCurrentFileEditor();
    if (!(editor instanceof DesignerEditor)) {
      return null;
    }
    DesignerEditor<?, ?, ?, ?, ?> designerEditor = (DesignerEditor<?, ?, ?, ?, ?>) editor;
    return designerEditor.getComponents().get(componentName);
  }

  // ─── Public operations ────────────────────────────────────────────────

  public static boolean deleteComponent(String componentName) {
    try {
      MockComponent component = findComponent(componentName);
      if (component == null || component.isRoot()) {
        return false;
      }
      component.delete();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean renameComponent(String oldName, String newName) {
    try {
      if (newName == null || newName.equals(oldName)) {
        return false;
      }
      MockComponent component = findComponent(oldName);
      if (component == null || component.isRoot()) {
        return false;
      }
      // Mirror the validation done by MockComponent.RenameDialog so we never
      // produce a project state that the dialog would have rejected.
      if (!TextValidators.isValidComponentIdentifier(newName)) {
        return false;
      }
      if (TextValidators.isReservedName(newName)) {
        return false;
      }
      FileEditor editor = Ode.getInstance().getCurrentFileEditor();
      if (editor instanceof DesignerEditor) {
        if (((DesignerEditor<?, ?, ?, ?, ?>) editor).getComponentNames().contains(newName)) {
          return false; // duplicate
        }
      }
      if (SimpleComponentDatabase.getInstance().isComponent(newName)) {
        return false; // collides with a component-type name (e.g. "Button")
      }
      component.rename(newName);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean setComponentProperty(
      String componentName, String propertyName, String value) {
    try {
      if (propertyName == null) {
        return false;
      }
      MockComponent component = findComponent(componentName);
      if (component == null) {
        return false;
      }
      if (!component.hasProperty(propertyName)) {
        return false;
      }
      // value can legitimately be the empty string (e.g. clearing Text).
      // Property type coercion is the editor's job — we pass the raw string.
      component.changeProperty(propertyName, value == null ? "" : value);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // ─── Read APIs (Phase 5) ──────────────────────────────────────────────
  // Each returns a JSON string. Lists return "[]" on failure (no project,
  // wrong editor, etc.) rather than throwing — JS callers can treat empty
  // arrays as the universal "nothing here" response. Single values return
  // "null" (the JSON literal) for missing data.

  /** Returns JSON array of {name, type} for every component on the active screen. */
  public static String getComponents() {
    try {
      FileEditor editor = Ode.getInstance().getCurrentFileEditor();
      if (!(editor instanceof DesignerEditor)) {
        return "[]";
      }
      DesignerEditor<?, ?, ?, ?, ?> designerEditor = (DesignerEditor<?, ?, ?, ?, ?>) editor;
      StringBuilder sb = new StringBuilder("[");
      String sep = "";
      for (MockComponent comp : designerEditor.getComponents().values()) {
        sb.append(sep).append("{")
          .append("\"name\":").append(JSONUtil.toJson(comp.getName()))
          .append(",\"type\":").append(JSONUtil.toJson(comp.getType()))
          .append("}");
        sep = ",";
      }
      sb.append("]");
      return sb.toString();
    } catch (Exception e) {
      return "[]";
    }
  }

  /**
   * Reads a single property value as a JSON string. Returns the JSON literal
   * "null" if the component or property doesn't exist. Numeric/boolean values
   * are still wrapped as JSON strings — App Inventor stores all property
   * values as strings internally and lets the property editor coerce.
   */
  public static String getComponentProperty(String componentName, String propertyName) {
    try {
      if (propertyName == null) {
        return "null";
      }
      MockComponent comp = findComponent(componentName);
      if (comp == null || !comp.hasProperty(propertyName)) {
        return "null";
      }
      String value = comp.getPropertyValue(propertyName);
      return value == null ? "null" : JSONUtil.toJson(value);
    } catch (Exception e) {
      return "null";
    }
  }

  /** Returns JSON array of screen names ("Screen1", "Screen2", ...). */
  public static String listScreens() {
    try {
      FileEditor editor = Ode.getInstance().getCurrentFileEditor();
      if (editor == null) {
        return "[]";
      }
      ProjectRootNode root = editor.getProjectRootNode();
      if (root == null) {
        return "[]";
      }
      StringBuilder sb = new StringBuilder("[");
      String sep = "";
      for (ProjectNode child : root.getAllSourceNodes()) {
        if (child instanceof YoungAndroidBlocksNode) {
          // YoungAndroidBlocksNode has a getFormName() that strips the
          // ".bky" suffix — preferred over getName() which keeps it.
          YoungAndroidBlocksNode blocksNode = (YoungAndroidBlocksNode) child;
          String name = blocksNode.getFormName();
          if (name != null) {
            sb.append(sep).append(JSONUtil.toJson(name));
            sep = ",";
          }
        }
      }
      sb.append("]");
      return sb.toString();
    } catch (Exception e) {
      return "[]";
    }
  }

  /** Returns JSON array of asset filenames ("kitty.png", "music.mp3", ...). */
  public static String listAssets() {
    try {
      YoungAndroidAssetsFolder assetsFolder = getAssetsFolder();
      if (assetsFolder == null) {
        return "[]";
      }
      StringBuilder sb = new StringBuilder("[");
      String sep = "";
      for (ProjectNode asset : assetsFolder.getChildren()) {
        // asset.getName() returns the bare filename (no path prefix).
        String name = asset.getName();
        if (name != null) {
          sb.append(sep).append(JSONUtil.toJson(name));
          sep = ",";
        }
      }
      sb.append("]");
      return sb.toString();
    } catch (Exception e) {
      return "[]";
    }
  }

  /**
   * Returns the persisted Blockly XML for the *current* blocks editor (or "" if
   * the user is in designer view or no project is open). Per-screen reads
   * would require switching the active editor, which we avoid here — embedders
   * who need a different screen's blocks should call switchScreen first.
   */
  public static String getBlocksXml() {
    try {
      FileEditor editor = Ode.getInstance().getCurrentFileEditor();
      if (!(editor instanceof BlocksEditor)) {
        // Maybe user is in designer; the matching blocks editor for the
        // current screen is reachable via getCurrentFileEditor()'s sibling.
        // For Phase 5 we return empty rather than synthesizing — the embedder
        // can switchScreen / switchToBlocks first.
        return "";
      }
      BlocksEditor<?, ?> blocksEditor = (BlocksEditor<?, ?>) editor;
      String content = blocksEditor.getRawFileContent();
      return content == null ? "" : content;
    } catch (Exception e) {
      return "";
    }
  }

  /** Removes an asset by filename. Returns true on success, false if not found. */
  public static boolean deleteAsset(String filename) {
    try {
      if (filename == null || filename.isEmpty()) {
        return false;
      }
      YoungAndroidAssetsFolder assetsFolder = getAssetsFolder();
      if (assetsFolder == null) {
        return false;
      }
      Project project = Ode.getInstance().getProjectManager()
          .getProject(Ode.getInstance().getCurrentYoungAndroidProjectId());
      if (project == null) {
        return false;
      }
      for (ProjectNode asset : assetsFolder.getChildren()) {
        if (filename.equals(asset.getName()) && asset instanceof YoungAndroidAssetNode) {
          project.deleteNode(asset);
          return true;
        }
      }
      return false; // no matching asset
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Move a component to a new parent container (and optional visible
   * index). Returns true if the move succeeded.
   *
   * - componentName: instance name of the source component
   * - newParentName: instance name of the destination container, or
   *   "Screen1" to move to the form root
   * - beforeIndex: visible-index in the new parent to insert before;
   *   -1 appends. Indices reference shown-only children, matching
   *   MockContainer.addVisibleComponent's contract.
   *
   * Mirrors what AI's drag-and-drop reorder does: detach via
   * source-container.removeComponent(component, false) then re-attach
   * via target.addVisibleComponent(...).
   */
  public static boolean moveComponent(String componentName, String newParentName, int beforeIndex) {
    try {
      MockComponent component = findComponent(componentName);
      if (component == null || component.isRoot()) return false;
      MockComponent newParent = findComponent(newParentName);
      if (!(newParent instanceof com.google.appinventor.client.editor.simple.components.MockContainer)) {
        return false;
      }
      com.google.appinventor.client.editor.simple.components.MockContainer target =
          (com.google.appinventor.client.editor.simple.components.MockContainer) newParent;
      if (!target.willAcceptComponentType(component.getType())) return false;
      com.google.appinventor.client.editor.simple.components.MockContainer source = component.getContainer();
      if (source == null) return false;
      // No-op if already at the same position to avoid pointless writes.
      if (source == target) {
        java.util.List<com.google.appinventor.client.editor.simple.components.MockComponent> visible =
            target.getShowingVisibleChildren();
        int currentIdx = visible.indexOf(component);
        if (currentIdx == beforeIndex ||
            (beforeIndex == -1 && currentIdx == visible.size() - 1)) {
          return true;
        }
      }
      source.removeComponent(component, false);
      target.addVisibleComponent(component, beforeIndex);
      // Mark the form dirty so saving picks up the structural change.
      target.getForm().select(null);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // ─── Internal helpers ─────────────────────────────────────────────────

  private static YoungAndroidAssetsFolder getAssetsFolder() {
    long projectId = Ode.getInstance().getCurrentYoungAndroidProjectId();
    if (projectId == 0) {
      return null;
    }
    Project project = Ode.getInstance().getProjectManager().getProject(projectId);
    if (project == null) {
      return null;
    }
    ProjectRootNode root = project.getRootNode();
    if (!(root instanceof YoungAndroidProjectNode)) {
      return null;
    }
    return ((YoungAndroidProjectNode) root).getAssetsFolder();
  }

  // ─── JSNI export ──────────────────────────────────────────────────────

  private static native void exportMethodsToJavascript() /*-{
    $wnd.ComponentEditor_deleteComponent =
      $entry(@com.google.appinventor.client.ComponentEditor::deleteComponent(Ljava/lang/String;));
    $wnd.ComponentEditor_renameComponent =
      $entry(@com.google.appinventor.client.ComponentEditor::renameComponent(Ljava/lang/String;Ljava/lang/String;));
    $wnd.ComponentEditor_setComponentProperty =
      $entry(@com.google.appinventor.client.ComponentEditor::setComponentProperty(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;));
    $wnd.ComponentEditor_getComponents =
      $entry(@com.google.appinventor.client.ComponentEditor::getComponents());
    $wnd.ComponentEditor_getComponentProperty =
      $entry(@com.google.appinventor.client.ComponentEditor::getComponentProperty(Ljava/lang/String;Ljava/lang/String;));
    $wnd.ComponentEditor_listScreens =
      $entry(@com.google.appinventor.client.ComponentEditor::listScreens());
    $wnd.ComponentEditor_listAssets =
      $entry(@com.google.appinventor.client.ComponentEditor::listAssets());
    $wnd.ComponentEditor_getBlocksXml =
      $entry(@com.google.appinventor.client.ComponentEditor::getBlocksXml());
    $wnd.ComponentEditor_deleteAsset =
      $entry(@com.google.appinventor.client.ComponentEditor::deleteAsset(Ljava/lang/String;));
    $wnd.ComponentEditor_moveComponent =
      $entry(@com.google.appinventor.client.ComponentEditor::moveComponent(Ljava/lang/String;Ljava/lang/String;I));
  }-*/;
}
