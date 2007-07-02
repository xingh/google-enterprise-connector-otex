// Copyright (C) 2007 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.otex;

import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import javax.swing.text.MutableAttributeSet;
import com.google.enterprise.connector.spi.ConfigureResponse;
import junit.framework.TestCase;

/**
 * Tests the LivelinkConnectorType implementation.
 */
public class LapiLivelinkConnectorTypeTest
        extends CoreLivelinkConnectorTypeTest {
    /**
     * Tests the validateConfig method with input which doesn't map to
     * a valid Livelink server. Overrides the same method from
     * LivelinkConnectorType.
     */
    public void testValidateConfigInvalidLivelinkInput() throws Exception {
        HashMap props = new HashMap(emptyProperties);
        props.put("server", "myhost");
        props.put("port", "123");
        props.put("username", "me");
        props.put("password", "pw");
        ConfigureResponse response =
            connectorType.validateConfig(props, defaultLocale);
        assertNotNull("Missing ConfigureResponse", response);
        HashMap form = getForm(response);
        assertValue(form, "server", "myhost");
        assertValue(form, "port", "123");
        assertValue(form, "username", "me");
        assertValue(form, "password", "pw");
        assertBooleanIsTrue(form, "https");
        assertIsHidden(form, "authenticationServer");
    }
}
