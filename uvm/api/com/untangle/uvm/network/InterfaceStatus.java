/**
 * $Id: InterfaceSettings.java 32043 2012-05-31 21:31:47Z dmorris $
 */
package com.untangle.uvm.network;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.net.InetAddress;

import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.json.JSONString;

import com.untangle.uvm.node.IPMaskedAddress;

/**
 * This object represents the current status/config of an interface.
 * This is not a settings object.
 */
@SuppressWarnings("serial")
public class InterfaceStatus implements Serializable, JSONString
{
    private InetAddress v4Address = null;
    private InetAddress v4Netmask = null;
    private InetAddress v4Gateway = null;
    private InetAddress v4Dns1 = null;
    private InetAddress v4Dns2 = null;

    private InetAddress v6Address = null;
    private InetAddress v6PrefixLength = null;
    private InetAddress v6Gateway = null;

    public InterfaceStatus() {}
    
    public String toJSONString()
    {
        JSONObject jO = new JSONObject(this);
        return jO.toString();
    }
    
    public InetAddress getV4Address( ) { return this.v4Address; }
    public void setV4Address( InetAddress newValue ) { this.v4Address = newValue; }

    public InetAddress getV4Netmask( ) { return this.v4Netmask; }
    public void setV4Netmask( InetAddress newValue ) { this.v4Netmask = newValue; }
    
    public InetAddress getV4Gateway( ) { return this.v4Gateway; }
    public void setV4Gateway( InetAddress newValue ) { this.v4Gateway = newValue; }
    
    public InetAddress getV4Dns1( ) { return this.v4Dns1; }
    public void setV4Dns1( InetAddress newValue ) { this.v4Dns1 = newValue; }

    public InetAddress getV4Dns2( ) { return this.v4Dns2; }
    public void setV4Dns2( InetAddress newValue ) { this.v4Dns2 = newValue; }

    public InetAddress getV6Address( ) { return this.v6Address; }
    public void setV6Address( InetAddress newValue ) { this.v6Address = newValue; }

    public InetAddress getV6PrefixLength( ) { return this.v6PrefixLength; }
    public void setV6PrefixLength( InetAddress newValue ) { this.v6PrefixLength = newValue; }

    public InetAddress getV6Gateway( ) { return this.v6Gateway; }
    public void setV6Gateway( InetAddress newValue ) { this.v6Gateway = newValue; }
    
}
