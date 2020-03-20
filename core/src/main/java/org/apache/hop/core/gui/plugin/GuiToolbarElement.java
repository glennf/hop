package org.apache.hop.core.gui.plugin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation allows a method in a GuiPlugin to be identified as a contributor to the Hop UI
 */
@Documented
@Retention( RetentionPolicy.RUNTIME )
@Target( { ElementType.FIELD, ElementType.METHOD } )
public @interface GuiToolbarElement {

  /**
   * Every GUI Element has a unique ID so it can be replaced by other plugins at any given time.
   *
   * @return The unique ID of the GUI Element
   */
  String id();

  /**
   * The type of GUI Element this method covers for
   *
   * @return
   */
  GuiElementType type();

  /**
   * The parent in which this annotation resides. Normally simply the class name is taken but plugins can pick a specific parent.
   *
   * @return
   */
  String parent() default "";

  /**
   * The label of the GUI element: the menu item text and so on.
   *
   * @return The GUI Element Label
   */
  String label() default "";

  /**
   * The tooltip of the GUI element (when applicable)
   *
   * @return The GUI Element tooltip for the widget and the label
   */
  String toolTip() default "";

  /**
   * The class name of the i18n package class for the label and tooltip
   */
  Class<?> i18nPackageClass() default Void.class;

  /**
   * The name of the i18n package class for the label and tooltip
   */
  String i18nPackage() default "";


  /**
   * The image filename of the GUI Element, usually an SVG icon.
   *
   * @return The image for the toolbar icon
   */
  String image() default "";

  /**
   * The disabled image filename of the GUI Element, usually an SVG icon.
   *
   * @return The disabled image
   */
  String disabledImage() default "";

  /**
   * The ID of the parent GUI element. This is usually the parent menu or toolbar, ...
   *
   * @return The ID of the parent GUI element
   */
  String parentId() default "";

  /**
   * @return True if the text element you define is a password with an asterisk mask
   */
  boolean password() default false;

  /**
   * @return true if the widget supports variables
   */
  boolean variables() default true;

  /**
   * @return The getter method of the property if it's non-standard
   */
  String getterMethod() default "";

  /**
   * @return The setter method of the property if it's non-standard
   */
  String setterMethod() default "";

  /**
   * @return The method which returns a String[] to populate a combo box widget GUI element
   */
  String comboValuesMethod() default "";

  /**
   * You can use this to order the GUI Elements for a given scenario
   *
   * @return The value on which the system will sort alphabetically
   */
  String order() default "";

  /**
   * Set this flag to true if you want to ignore the field as a GUI Element.
   * You can use this to override a GUI element from a base class.
   *
   * @return True if you want this element to be ignored
   */
  boolean ignored() default false;

  /**
   * Set to true if you want the menu-item of toolbar icon to be preceded by a separator or space.
   *
   * @return True if you want a separator before this element
   */
  boolean separator() default false;
}
