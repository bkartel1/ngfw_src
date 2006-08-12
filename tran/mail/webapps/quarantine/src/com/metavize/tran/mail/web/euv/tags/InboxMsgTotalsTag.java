/*
 * Copyright (c) 2005 Metavize Inc.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Metavize Inc. ("Confidential Information").  You shall
 * not disclose such Confidential Information.
 *
 * $Id$
 */
package com.metavize.tran.mail.web.euv.tags;

import com.metavize.tran.mail.papi.quarantine.InboxRecordCursor;


/**
 * Outputs the total number and size of records in the current index, or
 * 0 of there is no current index
 *
 */
public final class InboxMsgTotalsTag
  extends SingleValueTag {

  @Override
  protected String getValue() {
    InboxRecordCursor iCursor = InboxIndexTag.getCurrentIndex(pageContext.getRequest());
    try {
        return Integer.toString(iCursor == null ? 0 : iCursor.size()) + " mails (" +
          String.format("%01.3f", new Float(iCursor.inboxSize() / 1024.0)) + " KB)";
    }
    catch(Exception ex) { return "<unknown> mails, <unknown> KB"; }
  }
}
