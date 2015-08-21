/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.editors.theme.attributes.editors;

import com.android.ide.common.rendering.api.ItemResourceValue;
import com.android.ide.common.resources.ResourceResolver;
import com.android.tools.idea.configurations.Configuration;
import com.android.tools.idea.editors.theme.ThemeEditorContext;
import com.android.tools.idea.editors.theme.ThemeEditorUtils;
import com.android.tools.idea.editors.theme.datamodels.ThemeEditorStyle;
import com.android.tools.idea.rendering.ResourceHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class StyleListPaletteCellRenderer extends StyleListCellRenderer {
  private static final String PRIMARY_MATERIAL = "colorPrimary";
  private static final String PRIMARY_DARK_MATERIAL = "colorPrimaryDark";
  private static final String ACCENT_MATERIAL = "colorAccent";

  private ColorPaletteComponent myColorPaletteComponent = null;
  private final @NotNull ItemHoverListener myItemHoverListener;
  private final @NotNull ThemeEditorContext myContext;

  public interface ItemHoverListener {
    void itemHovered(@NotNull String name);
  }

  public StyleListPaletteCellRenderer(@NotNull ThemeEditorContext context,
                                      @NotNull ItemHoverListener itemHoverListener,
                                      @Nullable JComboBox comboBox) {
    super(context, comboBox);
    myContext = context;
    myColorPaletteComponent = new ColorPaletteComponent();
    myItemHoverListener = itemHoverListener;
  }

  @Override
  protected void customizeCellRenderer(JList list, Object value, int index, boolean selected, boolean hasFocus) {
    super.customizeCellRenderer(list, value, index, selected, hasFocus);

    if (!(value instanceof ThemeEditorStyle)) {
      myColorPaletteComponent.reset();
      return;
    }

    final ThemeEditorStyle theme = (ThemeEditorStyle)value;
    final boolean isFrameworkAttr = !ThemeEditorUtils.isAppCompatTheme(theme);

    ItemResourceValue primaryResourceValue = ThemeEditorUtils.resolveItemFromParents(theme, PRIMARY_MATERIAL, isFrameworkAttr);
    ItemResourceValue primaryDarkResourceValue =
      ThemeEditorUtils.resolveItemFromParents(theme, PRIMARY_DARK_MATERIAL, isFrameworkAttr);
    ItemResourceValue accentResourceValue = ThemeEditorUtils.resolveItemFromParents(theme, ACCENT_MATERIAL, isFrameworkAttr);

    //Check needed in case the xml files are inconsistent and have an item, but not a value
    if (primaryResourceValue != null && primaryDarkResourceValue != null && accentResourceValue != null) {
      Configuration configuration = theme.getConfiguration();
      ResourceResolver resourceResolver = configuration.getConfigurationManager().getResolverCache()
        .getResourceResolver(configuration.getTarget(), theme.getQualifiedName(), configuration.getFullConfig());

      Color primaryColor = ResourceHelper.resolveColor(resourceResolver, primaryResourceValue, myContext.getProject());
      Color primaryDarkColor = ResourceHelper.resolveColor(resourceResolver, primaryDarkResourceValue, myContext.getProject());
      Color accentColor = ResourceHelper.resolveColor(resourceResolver, accentResourceValue, myContext.getProject());

      if (primaryColor != null && primaryDarkColor != null && accentColor != null) {
        myColorPaletteComponent.setValues(primaryColor, primaryDarkColor, accentColor);
      }
    }else{
      myColorPaletteComponent.reset();
    }

    if (selected) {
      myItemHoverListener.itemHovered(theme.getQualifiedName());
    }
    setIcon(myColorPaletteComponent);
  }
}