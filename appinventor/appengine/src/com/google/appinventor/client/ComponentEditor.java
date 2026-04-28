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
import com.google.appinventor.client.editor.designer.DesignerEditor;
import com.google.appinventor.client.editor.simple.SimpleComponentDatabase;
import com.google.appinventor.client.editor.simple.components.MockComponent;
import com.google.appinventor.client.youngandroid.TextValidators;

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

  // ─── JSNI export ──────────────────────────────────────────────────────

  private static native void exportMethodsToJavascript() /*-{
    $wnd.ComponentEditor_deleteComponent =
      $entry(@com.google.appinventor.client.ComponentEditor::deleteComponent(Ljava/lang/String;));
    $wnd.ComponentEditor_renameComponent =
      $entry(@com.google.appinventor.client.ComponentEditor::renameComponent(Ljava/lang/String;Ljava/lang/String;));
    $wnd.ComponentEditor_setComponentProperty =
      $entry(@com.google.appinventor.client.ComponentEditor::setComponentProperty(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;));
  }-*/;
}
