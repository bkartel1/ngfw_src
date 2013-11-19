/**
 * $Id$
 */
package com.untangle.node.http;

import java.nio.ByteBuffer;

import com.untangle.node.token.Token;


/**
 * Holds a RFC 2616 status-line.
 *
 */
public class StatusLine implements Token
{
    private final String httpVersion;
    private final int statusCode;
    private final String reasonPhrase;

    public StatusLine(String httpVersion, int statusCode, String reasonPhrase)
    {
        this.httpVersion = httpVersion;
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
    }

    public String getHttpVersion()
    {
        return httpVersion;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public String getReasonPhrase()
    {
        return reasonPhrase;
    }

    // Token methods ----------------------------------------------------------

    public ByteBuffer getBytes()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(httpVersion).append(" ").append(statusCode)
            .append(" ").append(reasonPhrase).append("\r\n");
        byte[] buf = sb.toString().getBytes();

        return ByteBuffer.wrap(buf);
    }

    public int getEstimatedSize()
    {
        return httpVersion.length() + 3 + reasonPhrase.length();
    }
}
