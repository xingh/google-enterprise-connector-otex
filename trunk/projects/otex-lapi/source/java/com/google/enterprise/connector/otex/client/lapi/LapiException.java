// Copyright (C) 2006-2007 Google Inc.

package com.google.enterprise.connector.otex.client.lapi;

import java.text.MessageFormat;
import java.util.logging.Logger;

import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.otex.LivelinkException;

import com.opentext.api.LLSession;

/**
 * Extends <code>LivelinkException</code> to implement retrieving
 * error messages from the Livelink server.
 */
class LapiException extends LivelinkException
{
    /**
     * Builds a message from the various pieces of information
     * provided by the Livelink server.
     *
     * @param session the Livelink session on which the error occurred
     */
    private static String buildMessage(LLSession session) {
        int status = session.getStatus();
        if (status != 0) {
            String message = session.getStatusMessage();

            String apiError = session.getApiError();
            if (apiError == null || apiError.equals("")) {
                return message + " (" + status + ')';
            } else {
                String errorMessage = session.getErrMsg();
                return message + " (" + status + ") " + errorMessage +
                    " (" + apiError + ')';
            }
        } else
            return null;
    }
    
    /**
     * Constructs an instance that wraps another exception.
     * 
     * @param e a Livelink-specific runtime exception
     * @param logger a logger instance to log the exception against
     */
    LapiException(Exception e, Logger logger) {
        super(e, logger);
    }

    /**
     * Constructs an instance from a Livelink session that has
     * returned an error.
     * 
     * @param session the Livelink session on which the error occurred
     * @param logger a logger instance to log the exception against
     */
    LapiException(LLSession session, Logger logger) {
        super(buildMessage(session), logger);
    }
}
