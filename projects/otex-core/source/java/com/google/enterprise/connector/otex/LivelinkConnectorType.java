// Copyright (C) 2007-2008 Google Inc.
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

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.google.enterprise.connector.spi.ConfigureResponse;
import com.google.enterprise.connector.spi.ConnectorFactory;
import com.google.enterprise.connector.spi.ConnectorType;
import org.springframework.beans.PropertyAccessException;
import org.springframework.beans.PropertyBatchUpdateException;

/**
 * Supports the configuration properties used by the Livelink Connector.
 */
public class LivelinkConnectorType implements ConnectorType {

    /** The connector properties version property name. */
    public static final String VERSION_PROPERTY =
        "LivelinkConnectorPropertyVersion";
    public static final String VERSION_NUMBER = "1";

    /** The logger for this class. */
    private static final Logger LOGGER =
        Logger.getLogger(LivelinkConnectorType.class.getName());

    /** An all-trusting TrustManager for SSL URL validation. */
    private static final TrustManager[] trustAllCerts =
        new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
                public void checkServerTrusted(
                    X509Certificate[] certs, String authType)
                    throws CertificateException {
                    return;
                }
                public void checkClientTrusted(
                    X509Certificate[] certs,
                    String authType)
                    throws CertificateException {
                    return;
                }
            }
        };

    /**
     * Holds information (name, label, default value) about a
     * configuration property. Handles displaying that property
     * in the format used by the GSA.
     */
    private static abstract class FormProperty {
        /**
         * Gets the display label for the given name.
         *
         * @param name a form element name or some other unique key
         * @return the display label, or if one cannot be found, the name
         */
        protected static String getLabel(String name, ResourceBundle labels) {
            if (labels == null)
                return name;
            else {
                try {
                    return labels.getString(name);
                } catch (MissingResourceException e) {
                    return name;
                }
            }
        }

        protected final String name;
        protected final boolean required;
        protected final String defaultValue;

        protected FormProperty(String name) {
            this(name, null);
        }

        protected FormProperty(String name, String defaultValue) {
            this(name, false, defaultValue);
        }

        protected FormProperty(String name, boolean required,
                String defaultValue) {
            this.name = name;
            this.required = required;
            this.defaultValue = defaultValue;
        }

        public final String toString() {
            StringBuffer buffer = new StringBuffer();
            addToBuffer(buffer, "", "", null, null);
            return buffer.toString();
        }

        abstract protected void addFormControl(StringBuffer buffer,
            String value, ResourceBundle labels);

        /*
         * XXX: Make the valign dependent on the control type? We want
         * it for radio buttons and text areas, but maybe not for
         * simple text and password fields.
         */
        public void addToBuffer(StringBuffer buffer, String labelPrefix,
                String labelSuffix, String value, ResourceBundle labels) {
            String label = getLabel(name, labels);

            // TODO: Use CSS here. Better handling of section labels.
            if (FormBuilder.START_TAG.equals(labelPrefix))
                buffer.append("<tr><td>&nbsp;</td><td>&nbsp;</td></tr>\r\n");
            buffer.append("<tr valign='top'>\r\n").
                append("<td style='white-space: nowrap'>");
            if (required)
                buffer.append("<div style='float: left;'>");
            buffer.append(labelPrefix);
            appendLabel(buffer, name, label);
            buffer.append(labelSuffix);
            if (required) {
                buffer.append("</div><div style='text-align: right; ").
                    append("color: red; font-weight: bold; ").
                    append("margin-right: 0.3em;\'>*</div>");
            }
            buffer.append("</td>\r\n").
                append("<td>");
            addFormControl(buffer, value, labels);
            buffer.append("</td>\r\n</tr>\r\n");
        }

        protected void appendOption(StringBuffer buffer, String value,
                String text, boolean isSelected) {
            buffer.append("<option ");
            appendAttribute(buffer, "value", value);
            if (isSelected)
                buffer.append("selected");
            buffer.append(">");
            escapeAndAppend(buffer, text);
            buffer.append("</option>");
        }

        /* We need to use double quotes around the attribute
         * value because the ConnectorManager uses a string match
         * to find 'name=\"' and add a prefix to the
         * configuration parameters coming from this connector
         * type. See
         * ConnectionManagerGetServlet.writeConfigureResponse and
         * ServletUtil.prependCmPrefix.
         */
        protected void appendAttribute(StringBuffer buffer, String name,
                String value) {
            buffer.append(name).append("=\"");
            escapeAndAppend(buffer, value);
            buffer.append("\" ");
        }

        protected void escapeAndAppend(StringBuffer buffer, String data) {
            for (int i = 0; i < data.length(); i++) {
                switch (data.charAt(i)) {
                case '\'': buffer.append("&apos;"); break;
                case '"': buffer.append("&quot;"); break;
                case '&': buffer.append("&amp;"); break;
                case '<': buffer.append("&lt;"); break;
                case '>': buffer.append("&gt;"); break;
                default: buffer.append(data.charAt(i));
                }
            }
        }

        protected void appendLabel(StringBuffer buffer, String element,
                String label) {
            buffer.append("<label ");
            appendAttribute(buffer, "for", element);
            buffer.append(">");
            buffer.append(label);
            buffer.append("</label>");
        }
    }

    /** A form label with no associated form control. */
    private static class LabelProperty extends FormProperty {
        LabelProperty(String name) {
            super(name);
        }

        /** Writes a table row with a two-column label and no form control. */
        public void addToBuffer(StringBuffer buffer, String labelPrefix,
                String labelSuffix, String value, ResourceBundle labels) {
            String label = getLabel(name, labels);

            // TODO: Handle duplication between this and
            // FormProperty.addToBuffer.
            // XXX: Should we just assume that we know that
            // labelPrefix = START_TAG and labelSuffix = END_TAG here?
            if (FormBuilder.START_TAG.equals(labelPrefix))
                buffer.append("<tr><td>&nbsp;</td><td>&nbsp;</td></tr>\r\n");
            buffer.append("<tr valign='top'>\r\n").
                append("<td style='white-space: nowrap' colspan='2'>");
            buffer.append(labelPrefix);
            appendLabel(buffer, name, label);
            buffer.append(labelSuffix);
            buffer.append("</td>\r\n</tr>\r\n");
        }

        /* This is never called, but it is abstract in our superclass. */
        protected void addFormControl(StringBuffer buffer, String value,
            ResourceBundle labels) {
        }
    }

    /**
     * Holder for a property which should be rendered as a text
     * input element.
     */
    private static class TextInputProperty extends FormProperty {
        private final String type;

        TextInputProperty(String name) {
            this(name, false);
        }

        TextInputProperty(String name, boolean required) {
            this(name, required, null);
        }

        TextInputProperty(String name, String defaultValue) {
            this(name, false, defaultValue);
        }

        TextInputProperty(String name, boolean required, String defaultValue) {
            this(name, required, defaultValue, "text");
        }

        protected TextInputProperty(String name, String defaultValue,
                String type) {
            this(name, false, defaultValue, type);
        }

        protected TextInputProperty(String name, boolean required,
                String defaultValue, String type) {
            super(name, required, defaultValue);
            this.type = type;
        }

        protected void addFormControl(StringBuffer buffer, String value,
            ResourceBundle labels) {

            buffer.append("<input ");
            appendAttribute(buffer, "type", type);
            appendAttribute(buffer, "name", name);

            // TODO: What size should this be? I made it larger to
            // reasonably handle displayUrl, but arguably it should be
            // larger than this. One inconsistency is that the
            // Connector Name field on the form has no size, so it
            // will have the smaller default size text box. I picked
            // 50 because the New Connector Manager form has elements
            // that size.
            appendAttribute(buffer, "size", "50");

            if (value == null) {
                if (defaultValue != null)
                    value = defaultValue;
                else
                    value = "";
            }
            appendAttribute(buffer, "value", value);
            buffer.append(" />");
        }
    }

    /**
     * Holder for a property which should be rendered as a password
     * input element.
     */
    private static class PasswordInputProperty extends TextInputProperty {
        PasswordInputProperty(String name) {
            this(name, false);
        }

        PasswordInputProperty(String name, boolean required) {
            super(name, required, null, "password");
        }
    }

    /**
     * Holder for a property which should be rendered as a hidden
     * input element.
     */
    private static class HiddenInputProperty extends TextInputProperty {
        HiddenInputProperty(FormProperty prop) {
            super(prop.name, prop.defaultValue, "hidden");
        }

        public void addToBuffer(StringBuffer buffer, String labelPrefix,
                String labelSuffix, String value, ResourceBundle labels) {
            buffer.append("<tr style='display:none'>\r\n").
                append("<td></td>\r\n<td>");
            addFormControl(buffer, value, labels);
            buffer.append("</td>\r\n</tr>\r\n");
        }
    }

    /**
     * Holder for a property which should be rendered as a textarea
     * input element.
     */
    private static class TextareaProperty extends FormProperty {
        TextareaProperty(String name) {
            super(name);
        }

        protected void addFormControl(StringBuffer buffer, String value,
            ResourceBundle labels) {

            buffer.append("<textarea ");
            appendAttribute(buffer, "rows", "5");
            appendAttribute(buffer, "cols", "40");
            appendAttribute(buffer, "name", name);
            buffer.append(">");
            if (value != null)
                escapeAndAppend(buffer, value);
            buffer.append("</textarea>");
        }
    }

    /**
     * Holder for a property which should be rendered as a set of
     * radio buttons.
     */
    private static class RadioSelectProperty extends FormProperty {
        private final String[] buttonNames;

        RadioSelectProperty(String name, String[] buttonNames,
                String defaultValue) {
            super(name, defaultValue);
            this.buttonNames = buttonNames;
        }

        protected void addFormControl(StringBuffer buffer, String value,
                ResourceBundle labels) {
            if (value == null)
                value = defaultValue;

            for (int i = 0; i < buttonNames.length; i++) {
                if (i > 0)	// arrange radio buttons vertically
                    buffer.append("<br>");
                String buttonName = buttonNames[i];
                buffer.append("<input ");
                appendAttribute(buffer, "type", "radio");
                appendAttribute(buffer, "name", name);
                appendAttribute(buffer, "id", name + buttonName);
                appendAttribute(buffer, "value", buttonName);
                if (buttonName.equalsIgnoreCase(value))
                    buffer.append("checked");
                buffer.append("> ");
                appendLabel(buffer, name + buttonName,
                            getLabel(buttonName, labels));
            }
        }
    }


    /**
     * Holder for a property which should be rendered as a radio button
     * selection with two values, "true" and "false".
     */
    private static class BooleanSelectProperty extends RadioSelectProperty {
        static final String[] boolstr = { "true", "false" };
        BooleanSelectProperty(String name, String defaultValue) {
            super(name, boolstr, defaultValue);
        }
    }


    /**
     * Holder for a property which should be rendered as a checkbox
     * whose values are "true"[checked] or "false"[unchecked].
     * Selecting an Enabler property usually results in a redisplay
     * that exposes additional properties.
     */
    private static class EnablerProperty extends FormProperty {
        EnablerProperty(String name, String defaultValue) {
            super(name, defaultValue);
        }

        protected void addFormControl(StringBuffer buffer, String value,
                ResourceBundle labels) {
            if (value == null)
                value = defaultValue;

            buffer.append("<input ");
            appendAttribute(buffer, "type", "checkbox");
            appendAttribute(buffer, "name", name);
            appendAttribute(buffer, "id", name + "true");
            appendAttribute(buffer, "value", "true");
            if ("true".equalsIgnoreCase(value))
                buffer.append("checked");
            buffer.append("> ");
            appendLabel(buffer, name + "true", getLabel("enable", labels));
        }
    }

    /**
     * Encapsulates form display properties.
     */
    private static class FormContext {
        private HashMap hiddenProperties;

        FormContext(Map configData) {
            hiddenProperties = new HashMap();
            hiddenProperties.put("ignoreDisplayUrlErrors",
                ignoreDisplayUrlErrors(configData) ? Boolean.FALSE : Boolean.TRUE);
        }

        void setHidden(String propertyName, boolean hide) {
            hiddenProperties.put(propertyName,
                hide ? Boolean.TRUE : Boolean.FALSE);
        }

        boolean isHidden(String propertyName) {
            Boolean b = (Boolean) hiddenProperties.get(propertyName);
            return b != null && b.booleanValue();
        }

        void setHideIgnoreDisplayUrlErrors(boolean hide) {
            setHidden("ignoreDisplayUrlErrors", hide);
        }

        boolean getHideIgnoreDisplayUrlErrors() {
            return isHidden("ignoreDisplayUrlErrors");
        }
    }


    /**
     * The Livelink Connector configuration form builder.
     */
    private static class FormBuilder {
        /** Configuration properties which are always displayed. */
        private static final ArrayList baseEntries;

        /** Label for the Livelink System Administrator properties. */
        private static final FormProperty adminLabel;

        /**
         * Configuration properties for the Livelink System
         * Administrator, which are always displayed.
         */
        private static final ArrayList adminEntries;

        /** Configuration properties which are never displayed. */
        private static final ArrayList hiddenEntries;

        /** Flag property for enabling the HTTP tunneling properties. */
        private static final FormProperty tunnelingEnabler;

        /**
         * Configuration properties for HTTP tunneling; displayed when the
         * useHttpTunneling property is set to "true".
         */
        private static final ArrayList tunnelingEntries;

        /**
         * Flag property for enabling the separate authentication
         * properties.
         */
        private static final FormProperty authenticationEnabler;

        /**
         * Configuration properties which are used for authentication;
         * displayed when the useSeparateAuthentication property was set
         * to "true".
         */
        private static final ArrayList authenticationEntries;
        /** Label for the Indexing Traversal properties. */
        private static final FormProperty indexingLabel;

        /** Configuration properties for Indexing Traversal properties. */
        private static final ArrayList indexingEntries;

        /** Start tag wrapper for the group labels. */
        /* Public because FormProperty needs it to detect the group headers. */
        public static final String START_TAG = "<b>";

        /** End tag wrapper for the group labels. */
        private static final String END_TAG = "</b>";

        /** Form indentation for the groups. */
        private static final String INDENTATION = "";

        static {
            baseEntries = new ArrayList();
            baseEntries.add(new TextInputProperty("server", true));
            baseEntries.add(new TextInputProperty("port", true, "2099"));
            /* Story 2888
            String tunnels[] = { "noTunneling", "httpTunneling",
                "httpsTunneling" };
            baseEntries.add(new RadioSelectProperty("enableHttpTunneling",
                                                    tunnels, tunnels[0]));
            */
            baseEntries.add(new TextInputProperty("displayUrl", true));
            baseEntries.add(
                new BooleanSelectProperty("ignoreDisplayUrlErrors", "false"));

            /* Story 2888
            String auths[] = { "livelinkAuthentication",
                               "httpBasicAuthentication",
                               "integratedWindowsAuthentication" };
            baseEntries.add(new RadioSelectProperty("userAuthentication",
                                                    auths, auths[0]));
            */

            adminLabel = new LabelProperty("livelinkAdmin");
            adminEntries = new ArrayList();
            adminEntries.add(new TextInputProperty("username", true));
            adminEntries.add(new PasswordInputProperty("Password", true));
            adminEntries.add(new TextInputProperty("domainName"));

            // These record the state of the enablers. They are a little
            // different from each other, because the useHttpTunneling
            // property is only used by LivelinkConnectorType and its
            // helpers. The useSeparateAuthentication property, on the
            // other hand, is passed along through the
            // connectorInstance.xml file to the LivelinkConnector class,
            // which uses it to determine whether any of the
            // authentication parameters should be used.
            hiddenEntries = new ArrayList();
            hiddenEntries.add(
                new BooleanSelectProperty("useHttpTunneling", "false"));
            hiddenEntries.add(
                new BooleanSelectProperty("useSeparateAuthentication",
                    "false"));

            tunnelingEnabler =
                new EnablerProperty("enableHttpTunneling", "false");
            tunnelingEntries = new ArrayList();
            tunnelingEntries.add(new TextInputProperty("livelinkCgi", true));
            tunnelingEntries.add(new TextInputProperty("httpUsername"));
            tunnelingEntries.add(new PasswordInputProperty("httpPassword"));
            tunnelingEntries.add(
                new BooleanSelectProperty("enableNtlm", "false"));
            tunnelingEntries.add(new BooleanSelectProperty("https", "false"));
            tunnelingEntries.add(
                new BooleanSelectProperty("useUsernamePasswordWithWebServer",
                    "false"));

            authenticationEnabler =
                new EnablerProperty("enableSeparateAuthentication", "false");

            authenticationEntries = new ArrayList();
            authenticationEntries.add(
                new TextInputProperty("authenticationServer", true));
            authenticationEntries.add(
                new TextInputProperty("authenticationPort", true, "80"));
            authenticationEntries.add(
                new TextInputProperty("authenticationLivelinkCgi", true));
            authenticationEntries.add(
                new BooleanSelectProperty("authenticationEnableNtlm", "false"));
            authenticationEntries.add(
                new BooleanSelectProperty("authenticationHttps", "false"));
            authenticationEntries.add(
                new TextInputProperty("authenticationDomainName"));
            authenticationEntries.add(
                new BooleanSelectProperty(
                    "authenticationUseUsernamePasswordWithWebServer",
                    "false"));

            // Indexing Traversal configuration
            indexingLabel = new LabelProperty("indexing");
            indexingEntries = new ArrayList();
            indexingEntries.add(
                new TextInputProperty("traversalUsername", false));
            indexingEntries.add(
                new TextInputProperty("includedLocationNodes"));
        }

        private final Map data;
        private final ResourceBundle labels;
        private FormContext formContext;

        FormBuilder(ResourceBundle labels) {
            this(labels, Collections.EMPTY_MAP,
                new FormContext(Collections.EMPTY_MAP));
        }

        FormBuilder(ResourceBundle labels, Map data,
                FormContext formContext) {
            this.labels = labels;
            this.data = data;
            this.formContext = formContext;
        }

        private String getProperty(String name) {
            return (String) data.get(name);
        }

        private void addEntries(StringBuffer buffer, ArrayList entries,
                boolean hide, String labelPrefix, String labelSuffix) {
            for (Iterator i = entries.iterator(); i.hasNext(); ) {
                FormProperty prop = (FormProperty) i.next();
                addEntry(buffer, prop, hide, labelPrefix, labelSuffix);
            }
        }

        private void addEntry(StringBuffer buffer, FormProperty prop,
                boolean hide, String labelPrefix, String labelSuffix) {
            if (hide)
                prop = new HiddenInputProperty(prop);
            if (formContext.isHidden(prop.name))
                prop = new HiddenInputProperty(prop);
            prop.addToBuffer(buffer, labelPrefix, labelSuffix,
                getProperty(prop.name), labels);
        }

        private boolean hide(String name) {
            String value = getProperty(name);
            return value == null || ! new Boolean(value).booleanValue();
        }

        String getFormSnippet() {
            StringBuffer buffer = new StringBuffer(4096);
            addEntries(buffer, baseEntries, false, "", "");
            addEntry(buffer, adminLabel, false, START_TAG, END_TAG);
            addEntries(buffer, adminEntries, false, INDENTATION, "");
            addEntries(buffer, hiddenEntries, true, null, null);
            addEntry(buffer, tunnelingEnabler, false, START_TAG, END_TAG);
            addEntries(buffer, tunnelingEntries, hide("useHttpTunneling"),
                INDENTATION, "");
            addEntry(buffer, authenticationEnabler, false, START_TAG, END_TAG);
            addEntries(buffer, authenticationEntries,
                hide("useSeparateAuthentication"), INDENTATION, "");
            addEntry(buffer, indexingLabel, false, START_TAG, END_TAG);
            addEntries(buffer, indexingEntries, false, INDENTATION, "");
            return buffer.toString();
        }
    }

    /**
     * No-args constructor for bean instantiation.
     */
    public LivelinkConnectorType() {
        super();
    }

    /**
     * Localizes the resource bundle name.
     *
     * @param locale the locale to look up
     * @return the ResourceBundle
     * @throws MissingResourceException if the bundle can't be found
     */
    private ResourceBundle getResources(Locale locale)
            throws MissingResourceException {
        return ResourceBundle.getBundle(
            "config.OtexConnectorResources", locale);
    }

    /**
     * Returns a form snippet containing the given string as an error message.
     *
     * @param error the error message to include
     * @return a ConfigureResponse consisting of a form snippet
     * with just an error message
     */
    private ConfigureResponse getErrorResponse(String error) {
        StringBuffer buffer = new StringBuffer(
            "<tr><td colspan=\"2\"><font color=\"red\">");
        buffer.append(error); // FIXME: HTML escaping?
        buffer.append("</font></td></tr>");
        return new ConfigureResponse(null, buffer.toString());
    }

    /**
     * {@inheritDoc}
     */
    public ConfigureResponse getConfigForm(Locale locale) {
        if (LOGGER.isLoggable(Level.CONFIG))
            LOGGER.config("getConfigForm locale: " + locale);
        try {
            return new ConfigureResponse(null,
                new FormBuilder(getResources(locale)).getFormSnippet());
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Failed to create config form", t);
            return getErrorResponse(getExceptionMessages(null, t));
        }
    }

    /**
     * {@inheritDoc}
     */
    public ConfigureResponse getPopulatedConfigForm(Map configData,
            Locale locale) {
        if (LOGGER.isLoggable(Level.CONFIG)) {
            LOGGER.config("getPopulatedConfigForm data: " +
                getMaskedMap(configData));
            LOGGER.config("getPopulatedConfigForm locale: " + locale);
        }
        try {
            return getResponse(null, getResources(locale), configData,
                new FormContext(configData));
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Failed to create config form", t);
            return getErrorResponse(getExceptionMessages(null, t));
        }
    }

    /**
     * Helper method for <code>getPopulatedConfigForm</code> and
     * <code>validateConfig</code>.
     *
     * @param message A message to be included to the user along with the form
     * @param configData A map of name, value pairs (String, String)
     * of configuration data
     * @param formContext A context which may be configured by
     * the caller to affect the form generation
     */
    private ConfigureResponse getResponse(String message,
            ResourceBundle bundle, Map configData, FormContext formContext) {
        if (LOGGER.isLoggable(Level.CONFIG))
            LOGGER.config("Response data: " + getMaskedMap(configData));
        if (message != null || formContext != null) {
            FormBuilder form = new FormBuilder(bundle, configData, formContext);
            return new ConfigureResponse(message, form.getFormSnippet());
        } else if (configData != null) {
            return new ConfigureResponse(null, null, configData);
        } else {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method will use the provided configuration
     * information to attempt to instantiate a LivelinkConnector
     * and call its <code>login</code> method. This allows this
     * method to determine whether the configuration information
     * provided is sufficient to connect to Livelink for
     * traversal. It does not attempt to validate the separate
     * authentication configuration, if any, since no separate
     * user information can be provided, and it's possible that
     * the traversal username isn't one that can be authenticated
     * using the authentication properties. For example,
     * traversal might be done as a Livelink admin user using the
     * Livelink server port, while authentication is done using a
     * web server. The Livelink admin user may not be a defined
     * user in the web server's authentication scheme.
     */
    /*
     * TODO: Add init method and parameter validation to
     * LivelinkConnector.
     */
    public ConfigureResponse validateConfig(Map configData, Locale locale,
            ConnectorFactory connectorFactory) {
        if (LOGGER.isLoggable(Level.CONFIG)) {
            LOGGER.config("validateConfig data: " + getMaskedMap(configData));
            LOGGER.config("validateConfig locale: " + locale);
        }

        try {
            ResourceBundle bundle = getResources(locale);
            FormContext formContext = new FormContext(configData);

            // We want to change the passed in properties, but avoid
            // changing the original configData parameter.
            HashMap config = new HashMap(configData);
            config.put(VERSION_PROPERTY, VERSION_NUMBER);

            // Update the properties to copy the enabler properties to
            // the uses.
            Boolean changeHttp = changeFormDisplay(config,
                "useHttpTunneling", "enableHttpTunneling");
            Boolean changeAuth = changeFormDisplay(config,
                "useSeparateAuthentication", "enableSeparateAuthentication");

            // Skip validation if one of the groups has been enabled.
            // The configuration is probably incomplete in this case,
            // and at least one more call to validateConfig will be
            // made, so we will eventually validate any of the other
            // changes that have been made this time.
            if (changeHttp == Boolean.TRUE || changeAuth == Boolean.TRUE) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.finest("SKIPPING VALIDATION: changeHttp = " +
                        changeHttp + "; changeAuth = " + changeAuth);
                }
                return getResponse(null, bundle, config, formContext);
            }

            // Instantiate a LivelinkConnector to check connectivity.
            LivelinkConnector conn = null;
            try {
                conn = (LivelinkConnector)
                    connectorFactory.makeConnector(config);
            } catch (Throwable t) {
                //                LOGGER.log(Level.WARNING, "Failed to create connector", t);
                LOGGER.log(Level.WARNING, "Failed to create connector: " + t.toString());
                t = t.getCause();
                while (t != null) {
                    if (t instanceof PropertyBatchUpdateException) {
                        PropertyAccessException[] pae =
                            ((PropertyBatchUpdateException) t).
                            getPropertyAccessExceptions();
                        for (int i = 0; i < pae.length; i++)
                            LOGGER.warning(pae[i].getMessage());
                    } else
                        LOGGER.warning(t.toString());
                    t = t.getCause();
                }
                return getResponse(failedInstantiation(bundle), bundle,
                                   config, formContext);
            }

            if (!ignoreDisplayUrlErrors(config)) {
                try {
                    String url = (String) config.get("displayUrl");
                    LOGGER.finer("Validating display URL " + url);
                    validateUrl(url, bundle);
                    url = conn.getPublicContentDisplayUrl();
                    LOGGER.finer("Validating public content display URL " + url);
                    validateUrl(url, bundle);
                } catch (UrlConfigurationException e) {
                    LOGGER.log(Level.WARNING, "Error in configuration", e);
                    formContext.setHideIgnoreDisplayUrlErrors(false);
                    return getResponse(
                        errorInConfiguration(bundle, e.getLocalizedMessage()),
                        bundle, config, formContext);
                }
            }

            try {
                conn.login();
            } catch (LivelinkException e) {
                // XXX: Should this be an errorInConfiguration error?
                return getResponse(e.getLocalizedMessage(bundle), bundle,
                    config, formContext);
            } catch (ConfigurationException c) {
                LOGGER.log(Level.WARNING, "Error in configuration", c);
                return getResponse(
                    errorInConfiguration(bundle, c.getLocalizedMessage(bundle)),
                    bundle, config, formContext);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Error in configuration", t);
                return getResponse(
                    errorInConfiguration(bundle, t.getLocalizedMessage()),
                    bundle, config, formContext);
            }

            // Return the OK configuration.
            return getResponse(null, null, config, null);

        } catch (Throwable t) {
            // One last catch to be sure we return a message.
            LOGGER.log(Level.SEVERE, "Failed to create config form", t);
            return getErrorResponse(getExceptionMessages(null, t));
        }
    }

    /**
     * Gets a copy of the map with password property values masked.
     *
     * @param original a property map
     * @return a copy of the map with password property values
     * replaced by the string "[...]"
     */
    private Map getMaskedMap(Map original) {
        HashMap copy = new HashMap();
        Iterator entries = original.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry entry = (Map.Entry) entries.next();
            String key = (String) entry.getKey();
            if (key.endsWith("assword"))
                copy.put(key, "[...]");
            else
                copy.put(key, entry.getValue());
        }
        return copy;
    }

    /**
     * Checks whether a property for showing and hiding other
     * parameters on the form has changed. If it has changed, the
     * current state property value is changed to match the requested
     * state.
     *
     * @param config the form properties
     * @param useName the name of the current state property
     * @param enableName the name of the requested state property
     * @return <code>Boolean.TRUE</code> if the property should be enabled,
     * <code>Boolean.FALSE</code> if the property should be disabled, or
     * <code>null</code> if it has not changed
     */
    private Boolean changeFormDisplay(Map config, String useName,
        String enableName) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("ENABLED " + enableName + ": " +
                        (String) config.get(enableName));
        }
        boolean enable =
            new Boolean((String) config.get(enableName)).booleanValue();
        boolean use = new Boolean((String) config.get(useName)).booleanValue();
        if (enable != use) {
            config.put(useName, enable ? "true" : "false");
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.fine("SETTING " + useName + ": " + enable);
            return enable ? Boolean.TRUE : Boolean.FALSE;
        } else
            return null;
    }

    /**
     * Formats and returns an errorInConfiguration message from
     * the given bundle.
     *
     * @param bundle the resource bundle
     * @param message the error message
     * @return the formatted error message
     * @throws MissingResourceException if the message isn't found
     */
    private String errorInConfiguration(ResourceBundle bundle,
            String message) {
        return MessageFormat.format(bundle.getString("errorInConfiguration"),
            new Object[] { message });
    }

    /**
     * Formats and returns a failedInstatiation message from
     * the given bundle.
     *
     * @param bundle the resource bundle
     * @param message the error message
     * @return the formatted error message
     * @throws MissingResourceException if the message isn't found
     */
    private String failedInstantiation(ResourceBundle bundle) {
        return bundle.getString("failedInstantiation");
    }

    /**
     * Formats and returns a httpNotFound message from the given
     * bundle.
     *
     * @param bundle the resource bundle
     * @param urlString the missing URL
     * @return the formatted error message
     * @throws MissingResourceException if the message isn't found
     */
    private String httpNotFound(ResourceBundle bundle, String urlString,
            int httpResponseCode, String httpResponseMessage) {
        return MessageFormat.format(bundle.getString("httpNotFound"),
            new Object[] { urlString, new Integer(httpResponseCode),
                           httpResponseMessage });
    }

    /**
     * Attempts to validate a URL. In this case, we're mostly
     * trying to catch typos, so "valid" means
     *
     * <ol>
     * <li>The URL syntax is valid.
     * <li>If the URL uses HTTP or HTTPS:
     *   <ol>
     *   <li>A connection can be made and the response read.
     *   <li>The response code was not 404,
     *   or any of the following related but less common errors:
     *   400, 405, 410, or 414.
     *   </ol>
     * </ol>
     *
     * The 405 (Method Not Allowed) is related because the Sun Java
     * System Web Server, and possibly Apache, return this code rather
     * than a 404 if you attempt to access a CGI program in an unknown
     * directory.
     *
     * When testing an HTTPS URL, we override server certificate
     * validation to skip trying to verify the server's
     * certificate. In this case, all we care about is that the
     * configured URL can be reached; it's up to the connector
     * administrator to enter the right URL.
     *
     * @param urlString the URL to test
     */
    /* Java 1.4 doesn't support setting a timeout on the
     * URLConnection. Java 1.5 does support timeouts, so we're
     * using reflection to set timeouts if available. Another
     * possibility would be to use Jakarta Commons HttpClient.
     *
     * The read and connect timeouts are set to one minute. This
     * isn't currently configurable, but if it fails the
     * connector admin can set the flag to ignore validation
     * errors and avoid the timeout problem.
     *
     * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4912484
     * The above Sun bug report documents that openConnection
     * doesn't try to connect.
     *
     * This method has package access and returns the HTTP response
     * code so that it can be unit tested. A return value of 0 is
     * arbitrary and unused by the tests.
     */
    int validateUrl(String urlString, ResourceBundle bundle)
            throws UrlConfigurationException {
        if (urlString == null || urlString.trim().length() == 0)
            return 0;

        try {
            URL url = new URL(urlString);
            URLConnection conn = url.openConnection();

            if (!(conn instanceof HttpURLConnection)) {
                // If the URL is not an HTTP or HTTPS URL, which is
                // incredibly unlikely, we don't check anything beyond
                // the URL syntax.
                return 0;
            }

            HttpURLConnection httpConn = (HttpURLConnection) conn;
            if (httpConn instanceof HttpsURLConnection)
                setTrustingTrustManager((HttpsURLConnection) httpConn);
            setTimeouts(conn);
            httpConn.setRequestMethod("HEAD");
            httpConn.setInstanceFollowRedirects(false);

            httpConn.connect();
            try {
                int responseCode = httpConn.getResponseCode();
                String responseMessage = httpConn.getResponseMessage();

                switch (responseCode) {
                case HttpURLConnection.HTTP_BAD_REQUEST:
                case HttpURLConnection.HTTP_NOT_FOUND:
                case HttpURLConnection.HTTP_BAD_METHOD:
                case HttpURLConnection.HTTP_GONE:
                case HttpURLConnection.HTTP_REQ_TOO_LONG:
                    if (LOGGER.isLoggable(Level.SEVERE)) {
                        LOGGER.severe("DISPLAY URL HTTP RESPONSE: " +
                            responseCode + " " + responseMessage);
                    }
                    throw new UrlConfigurationException(
                        httpNotFound(bundle, urlString, responseCode,
                            responseMessage));

                default:
                    if (LOGGER.isLoggable(Level.CONFIG)) {
                        LOGGER.config("DISPLAY URL HTTP RESPONSE: " +
                            responseCode + " " + responseMessage);
                    }
                    break;
                }
                return responseCode;
            } finally {
                httpConn.disconnect();
            }
        } catch (UrlConfigurationException u) {
            throw u;
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Error in Livelink URL validation", t);
            String text = getExceptionMessages(urlString, t);
            throw new UrlConfigurationException(text, t);
        }
    }

    /**
     * Replaces the default TrustManager for this connection with
     * one which trusts all certificates.
     *
     * @param conn the current connection
     * @throws Exception if an error occurs setting the properties
     */
    private void setTrustingTrustManager(HttpsURLConnection conn)
            throws Exception {
        SSLContext sc = SSLContext.getInstance("SSL");
        LOGGER.log(Level.FINEST, "SSLContext: " + sc);
        sc.init(null, trustAllCerts, null);
        SSLSocketFactory factory = sc.getSocketFactory();
        LOGGER.log(Level.FINEST, "SSLSocketFactory: " + factory);
        conn.setSSLSocketFactory(factory);
        LOGGER.log(Level.FINEST, "Using socket factory: " +
            conn.getSSLSocketFactory());
    }

    /**
     * Sets the connect and read timeouts of the given URLConnection.
     * This is only possible with Java SE 5.0 or later. With earlier
     * versions, we don't do anything.
     */
    private void setTimeouts(URLConnection conn) throws Exception {
        // If we're using Java 1.5 or later, URLConnection
        // has timeout methods.
        try {
            final Integer[] connectTimeoutArg = new Integer[] {
                new Integer(5 * 1000) };
            final Integer[] readTimeoutArg = new Integer[] {
                new Integer(60 * 1000) };
            Class c = conn.getClass();
            Method setConnectTimeout = c.getMethod("setConnectTimeout",
                new Class[] { int.class });
            setConnectTimeout.invoke(conn, (Object[]) connectTimeoutArg);
            Method setReadTimeout = c.getMethod("setReadTimeout",
                new Class[] { int.class });
            setReadTimeout.invoke(conn, (Object[]) readTimeoutArg);
        } catch (NoSuchMethodException m) {
            // Ignore; we're probably on Java 1.4.
            LOGGER.log(Level.FINEST,
                "No timeout methods; we're probably on Java 1.4.");
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Error setting connection timeout",
                t);
        }
    }

    /**
     * Returns the exception's message, or the exception class
     * name if no message is present.
     *
     * @param t the exception
     * @return a message
     */
    private String getExceptionMessage(Throwable t) {
        String message = t.getLocalizedMessage();
        if (message != null)
            return message;
        return t.getClass().getName();
    }

    /**
     * Returns a message containing the description text, the
     * message from the given exception, and any chained
     * exception messages.
     *
     * @param description the description text
     * @param t the exception
     * @return a message
     */
    private String getExceptionMessages(String description, Throwable t) {
        StringBuffer buffer = new StringBuffer();
        if (description != null)
            buffer.append(description).append(" ");
        buffer.append(getExceptionMessage(t)).append(" ");
        Throwable next = t;
        while ((next = next.getCause()) != null)
            buffer.append(getExceptionMessage(next));
        return buffer.toString();
    }

    /**
     * Return a boolean representing the state of the
     * ignoreDisplayUrlErrors configuration property.
     */
    private static boolean ignoreDisplayUrlErrors(Map config) {
        String value = (String) config.get("ignoreDisplayUrlErrors");
        if (value != null) {
            return new Boolean(value).booleanValue();
        }
        return false;
    }
}
