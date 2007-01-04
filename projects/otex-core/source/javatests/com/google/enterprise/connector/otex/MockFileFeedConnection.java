// Copyright 2006 Google Inc.  All Rights Reserved.

package com.google.enterprise.connector.otex;

import com.google.enterprise.connector.common.StringUtils;
import com.google.enterprise.connector.pusher.FeedConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

public class MockFileFeedConnection implements FeedConnection {

  StringBuffer buf = null;
  private final PrintStream printStream;
  
  public String getFeed() {
    String result;
    if (buf == null) {
      result = "";
    }
    result = buf.toString();
    buf = new StringBuffer(2048);
    return result;
  }

  public MockFileFeedConnection(PrintStream ps) {
    buf = new StringBuffer(2048);
    printStream = ps;
  }

  public String sendData(InputStream data) throws IOException {
    String dataStr = StringUtils.streamToString(data);
    buf.append(dataStr);
    printStream.println(dataStr);
    return "Mock response";
  }

}
