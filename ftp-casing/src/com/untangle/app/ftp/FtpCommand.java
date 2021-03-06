/**
 * $Id$
 */
package com.untangle.app.ftp;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import com.untangle.uvm.vnet.Token;
import com.untangle.uvm.util.AsciiCharBuffer;

/**
 * FTP command.
 * @see "RFC 959, Section 4"
 */
public class FtpCommand implements Token
{
    private final FtpFunction function;
    private final String argument;

    /**
     * Creates a new <code>FtpCommand</code> instance.
     *
     * @param function the FTP function. null if empty line.
     * @param argument function arguments. null if none, or empty line.
     */
    public FtpCommand(FtpFunction function, String argument)
    {
        this.function = function;
        this.argument = argument;
    }

    public InetSocketAddress getSocketAddress()
    {
        if (FtpFunction.PORT == function) {
            return FtpUtil.parsePort(argument);
        } else if (FtpFunction.EPRT==function) {
            return FtpUtil.parseExtendedPort(argument);
        } else {

            return null;
        }
    }

    public static FtpCommand portCommand(InetSocketAddress socketAddress)
    {
        String cmd = FtpUtil.unparsePort(socketAddress);
        return new FtpCommand(FtpFunction.PORT, cmd);
    }

    public static FtpCommand extendedPortCommand(InetSocketAddress socketAddress)
    {
        String cmd = FtpUtil.unparseExtendedPort(socketAddress);
        return new FtpCommand(FtpFunction.EPRT, cmd);
    }

    public FtpFunction getFunction()
    {
        return function;
    }

    public String getArgument()
    {
        return argument;
    }

    public ByteBuffer getBytes()
    {
        String cmd = null == function ? "" : function.toString();

        int l = cmd.length() + 2 + (null == argument ? 0 : argument.length());

        StringBuffer sb = new StringBuffer(l);
        sb.append(cmd);
        if (null != argument) {
            sb.append(' ');
            sb.append(argument);
        }
        sb.append("\r\n");
        return ByteBuffer.wrap(sb.toString().getBytes());
    }

    public String toString()
    {
        return AsciiCharBuffer.wrap(getBytes()).toString();
    }
}
