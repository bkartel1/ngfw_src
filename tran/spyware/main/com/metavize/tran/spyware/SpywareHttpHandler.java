/*
 * Copyright (c) 2004, 2005 Metavize Inc.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Metavize Inc. ("Confidential Information").  You shall
 * not disclose such Confidential Information.
 *
 * $Id$
 */

package com.metavize.tran.spyware;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.metavize.mvvm.MvvmContextFactory;
import com.metavize.mvvm.tapi.TCPSession;
import com.metavize.mvvm.tran.StringRule;
import com.metavize.tran.http.BlockingHttpStateMachine;
import com.metavize.tran.http.RequestLine;
import com.metavize.tran.http.StatusLine;
import com.metavize.tran.token.Chunk;
import com.metavize.tran.token.EndMarker;
import com.metavize.tran.token.Header;
import com.metavize.tran.token.Token;
import com.metavize.tran.util.AsciiCharBuffer;
import org.apache.log4j.Logger;

public class SpywareHttpHandler extends BlockingHttpStateMachine
{
    private static final Pattern OBJECT_PATTERN
        = Pattern.compile("<object", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLSID_PATTERN
        = Pattern.compile("clsid:([0-9\\-]*)", Pattern.CASE_INSENSITIVE);

    // XXX someone, make this pretty
    private static final String BLOCK_TEMPLATE
        = "<HTML><HEAD>"
        + "<TITLE>403 Forbidden</TITLE>"
        + "</HEAD><BODY>"
        + "<center><b>Metavize Spyware Blocker</b></center>"
        + "<p>This site blocked because it may be a spyware site.</p>"
        + "<p>Host: %s</p>"
        + "<p>URI: %s</p>"
        + "<p>Please contact your network administrator.</p>"
        + "<HR>"
        + "<ADDRESS>Metavize EdgeGuard</ADDRESS>"
        + "</BODY></HTML>";

    private final TCPSession session;

    private final List cookieQueue = new LinkedList();
    private final List reqHostQueue = new LinkedList();

    private final Logger logger = Logger.getLogger(getClass());
    private final Logger eventLogger = MvvmContextFactory.context()
        .eventLogger();

    private final SpywareImpl transform;

    private String extension = "";
    private String mimeType = "";

    // constructors -----------------------------------------------------------

    SpywareHttpHandler(TCPSession session, SpywareImpl transform)
    {
        super(session);

        this.transform = transform;
        this.session = session;
    }

    // HttpStateMachine methods -----------------------------------------------

    @Override
    protected RequestLine doRequestLine(RequestLine requestLine)
    {
        logger.debug("got request line");

        String path = requestLine.getRequestUri().getPath();
        int i = path.lastIndexOf('.');
        extension = (0 <= i && path.length() - 1 > i)
            ? path.substring(i + 1) : null;

        return requestLine;
    }

    @Override
    protected Header doRequestHeader(Header requestHeader)
    {
        logger.debug("got request header");

        // XXX we could check the request-uri for an absolute address too...
        RequestLine requestLine = getRequestLine();
        String host = requestHeader.getValue("host");
        URI uri = requestLine.getRequestUri();
        if (transform.isBlacklistDomain(host, uri)) {
            SpywareBlacklistEvent evt = new SpywareBlacklistEvent
                (getSession().id(), requestLine);
            eventLogger.info(evt);
            // XXX we could send a page back instead, this isn't really right
            logger.debug("detected spyware, shutting down");

            blockRequest(generateResponse(host, uri.toString(),
                                          isRequestPersistent()));
        } else {
            releaseRequest();
        }

        return clientCookie(requestLine, requestHeader);
    }

    @Override
    protected Chunk doRequestBody(Chunk chunk)
    {
        return chunk;
    }

    @Override
    protected void doRequestBodyEnd() { }

    @Override
    protected StatusLine doStatusLine(StatusLine statusLine)
    {
        releaseResponse();
        return statusLine;
    }

    @Override
    protected Header doResponseHeader(Header header)
    {
        logger.debug("got response header");
        mimeType = header.getValue("content-type");

        if (100 != getStatusLine().getStatusCode()) {
            header = serverCookie(getResponseRequest(), header);
            header  = addCookieKillers(header);
        }

        return header;
    }

    @Override
    protected Chunk doResponseBody(Chunk chunk)
    {
        logger.debug("got response body");
        if (null != mimeType && mimeType.equalsIgnoreCase("text/html")) {
            chunk = activeXChunk(getResponseRequest(), chunk);
        }

        return chunk;
    }

    @Override
    protected void doResponseBodyEnd()
    {
        logger.debug("got response body end");
    }

    // private methods --------------------------------------------------------

    private Token[] generateResponse(String host, String uri,
                                     boolean persistent)
    {
        String replacement = String.format(BLOCK_TEMPLATE, host, uri);

        Token response[] = new Token[4];

        // XXX make canned responses in constructor
        // XXX Do template replacement
        ByteBuffer buf = ByteBuffer.allocate(replacement.length());
        buf.put(replacement.getBytes()).flip();

        StatusLine sl = new StatusLine("HTTP/1.1", 403, "Forbidden");
        response[0] = sl;

        Header h = new Header();
        h.addField("Content-Length", Integer.toString(buf.remaining()));
        h.addField("Content-Type", "text/html");
        h.addField("Connection", persistent ? "Keep-Alive" : "Close");
        response[1] = h;

        Chunk c = new Chunk(buf);
        response[2] = c;

        response[3] = EndMarker.MARKER;

        return response;
    }

    // cookie stuff -----------------------------------------------------------

    private Header clientCookie(RequestLine requestLine, Header h)
    {
        logger.debug("checking client cookie");

        List cookieKillers = new LinkedList();;
        cookieQueue.add(cookieKillers);

        String host = h.getValue("host");
        List cookies = h.getValues("cookie"); // XXX cookie2 ???

        reqHostQueue.add(host);

        if (null == cookies) {
            return h;
        }

        for (Iterator i = cookies.iterator(); i.hasNext(); ) {
            transform.incrementCount(Spyware.COOKIE);
            String cookie = (String)i.next();
            Map m = CookieParser.parseCookie(cookie);
            String domain = (String)m.get("domain");
            if (null == domain) {
                domain = host;
            }

            long t0 = System.currentTimeMillis();
            boolean badDomain = transform.isBlockedCookie(domain);
            long t1 = System.currentTimeMillis();
            if (logger.isDebugEnabled())
                logger.debug("looked up domain in: " + (t1 - t0) + " ms");

            if (badDomain) {
                logger.debug("blocking cookie: " + domain);
                transform.incrementCount(Spyware.BLOCK);

                eventLogger.info(new SpywareCookieEvent(getSession().id(), requestLine, domain, true));
                i.remove();
                logger.debug("making cookieKiller: " + domain);
                cookieKillers.addAll(makeCookieKillers(cookie, host));
            }
        }

        return h;
    }

    private Header serverCookie(RequestLine rl, Header h)
    {
        logger.debug("checking server cookie");
        // XXX if deferred 0ttl cookie, send it and nullify

        String reqDomain = (String)reqHostQueue.remove(0);

        List setCookies = h.getValues("set-cookie");

        if (null == setCookies) { return h; }

        for (Iterator i = setCookies.iterator(); i.hasNext(); ) {
            transform.incrementCount(Spyware.COOKIE);
            String v = (String)i.next();

            logger.debug("handling server cookie: " + v);

            Map<String, String> m = CookieParser.parseCookie(v);
            String domain = m.get("domain");
            logger.debug("got domain: " + domain);
            if (null == domain) {
                logger.debug("NULL domain IN: " + m);
                for (String foo : m.keySet()) {
                    logger.debug("eq " + foo + "? " + foo.equals("domain"));
                }
                domain = reqDomain;
                logger.debug("using request domain: " + domain);
            }


            boolean badDomain = transform.isBlockedCookie(domain);

            if (badDomain) {
                logger.debug("cookie deleted: " + domain);
                transform.incrementCount(Spyware.BLOCK);
                eventLogger.info(new SpywareCookieEvent(getSession().id(), rl, domain, false));
                i.remove();
            } else {
                logger.debug("cookie not deleted: " + domain);
            }
        }

        return h;
    }

    private Header addCookieKillers( Header h )
    {
        List cookieKillers = (List)cookieQueue.remove(0);
        for (Iterator i = cookieKillers.iterator(); i.hasNext(); ) {
            String killer = (String)i.next();
            logger.debug("adding killer to header: " + killer);
            h.addField("Set-Cookie", killer);
        }

        return h;
    }

    private List makeCookieKillers(String c, String h)
    {
        logger.debug("making cookie killers");
        List l = new LinkedList();

        String cookieKiller = makeCookieKiller(c, h);
        l.add(cookieKiller);

        while (true) {
            cookieKiller = makeCookieKiller(c, "." + h);
            l.add(cookieKiller);
            logger.debug("added cookieKiller: " + cookieKiller);

            int i = h.indexOf('.');
            if (0 <= i && (i + 1) < h.length()) {
                h = h.substring(i + 1);
                i = h.indexOf('.');
                if (0 > i) { break; }
            } else {
                break;
            }
        }

        return l;
    }

    private String makeCookieKiller(String c, String h)
    {
        c = stripMaxAge(c);

        int i = c.indexOf(';');
        if (0 > i) {
            return c + "; path=/; domain=" + h + "; max-age=0";
        } else {
            return c.substring(0, i) + "; path=/; domain=" + h + "; max-age=0;" +
                c.substring(i, c.length());
        }
    }

    private String stripMaxAge(String c)
    {
        String cl = c.toLowerCase();
        int i = cl.indexOf("max-age");
        if (-1 == i) {
            return c;
        } else {
            int j = c.indexOf(';', i);
            return c.substring(0, i) + c.substring(j + 1, c.length());
        }
    }

    // ActiveX stuff ----------------------------------------------------------

    private Chunk activeXChunk(RequestLine rl, Chunk c)
    {
        logger.debug("scanning activeX chunk");

        ByteBuffer b = c.getData();
        AsciiCharBuffer cb = AsciiCharBuffer.wrap(b);
        Matcher m = OBJECT_PATTERN.matcher(cb);
        if (m.find()) {
            logger.debug("found activex tag");
            int os = m.start();
            m = CLSID_PATTERN.matcher(cb);

            if (!m.find(os)) {
                return c; // not a match
            }

            int cs = m.start();
            int ce = m.end();

            boolean block = transform.getSpywareSettings().getBlockAllActiveX();
            String ident = null;
            if (!block) {
                String clsid = m.group(1);
                long t0 = System.currentTimeMillis();
                StringRule rule = transform.getBlockedActiveX(clsid);
                long t1 = System.currentTimeMillis();
                logger.debug("looked up activeX in: " + (t1 - t0) + " ms");

                if (null != rule) {
                    transform.incrementCount(Spyware.ACTIVE_X);
                    block = rule.isLive();
                    ident = rule.getString();
                }

                if (block) {
                    logger.debug("blacklisted classid: " + clsid);
                } else {
                    logger.debug("not blacklisted classid: " + clsid);
                }
            } else {
                ident = "All ActiveX Blocked";
            }

            if (block) {
                logger.debug("blocking activeX");
                transform.incrementCount(Spyware.BLOCK);
                eventLogger.info(new SpywareActiveXEvent(getSession().id(), rl, ident));
                int len = findEnd(cb, os);
                if (-1 == len) {
                    logger.warn("chunk does not contain entire tag");
                    // XXX cut & buffer from start
                } else {
                    for (int i = 0; i < len; i++) {
                        cb.put(os + i, ' ');
                    }
                }
            }

            return c;
        } else {
            // no activex
            return c;
        }
    }

    private int findEnd(AsciiCharBuffer cb, int start)
    {
        AsciiCharBuffer dup = cb.duplicate();
        dup.position(dup.position() + start);
        int level = 0;
        while (dup.hasRemaining()) {
            assert 0 <= level;
            char c = dup.get();
            switch (c) {
            case '<':
                if (!dup.hasRemaining()) {
                    return -1;
                } else if ('/' == dup.get(dup.position())) {
                    dup.get();
                    level--;
                    if (0 == level) {
                        while (dup.hasRemaining()) {
                            c = dup.get();
                            if (c == '>') {
                                return dup.position() - (cb.position() + start);
                            }
                        }
                        return -1;
                    }
                } else {
                    level++;
                }
                break;
            case '/':
                if (!dup.hasRemaining()) {
                    return -1;
                } else if ('>' == dup.get(dup.position())) {
                    dup.get();
                    level--;
                }
                break;
            }

            if (0 == level) {
                return dup.position() - (cb.position() + start);
            }
        }

        return -1;
    }
}
