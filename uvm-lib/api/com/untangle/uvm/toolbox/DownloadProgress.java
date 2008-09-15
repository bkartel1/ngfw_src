/*
 * $HeadURL$
 * Copyright (c) 2003-2007 Untangle, Inc.
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License, version 2,
 * as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful, but
 * AS-IS and WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, TITLE, or
 * NONINFRINGEMENT.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Linking this library statically or dynamically with other modules is
 * making a combined work based on this library.  Thus, the terms and
 * conditions of the GNU General Public License cover the whole combination.
 *
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent modules,
 * and to copy and distribute the resulting executable under terms of your
 * choice, provided that you also meet, for each linked independent module,
 * the terms and conditions of the license of that module.  An independent
 * module is a module which is not derived from or based on this library.
 * If you modify this library, you may extend this exception to your version
 * of the library, but you are not obligated to do so.  If you do not wish
 * to do so, delete this exception statement from your version.
 */

package com.untangle.uvm.toolbox;

import com.untangle.uvm.message.Message;

/**
 * Update on the status of a download.
 *
 * @author <a href="mailto:amread@untangle.com">Aaron Read</a>
 * @version 1.0
 */
public class DownloadProgress extends Message
{
    private static final long serialVersionUID = -4416392955752833104L;

    private final String name;
    private final int bytesDownloaded;
    private final int size;
    private final String speed;
    private final boolean upgrade;

    public DownloadProgress(String name, int bytesDownloaded, int size,
                            String speed, boolean upgrade)
    {
        this.name = name;
        this.bytesDownloaded = bytesDownloaded;
        this.size = size;
        this.speed = speed;
        this.upgrade = upgrade;
    }

    // accessors --------------------------------------------------------------

    public String getName()
    {
        return name;
    }

    public int getBytesDownloaded()
    {
        return bytesDownloaded;
    }

    public int getSize()
    {
        return size;
    }

    public String getSpeed()
    {
        return speed;
    }

    public boolean isUpgrade()
    {
        return upgrade;
    }

    // Object methods ---------------------------------------------------------

    public String toString()
    {
        return "DownloadProgress name: " + name
            + " bytesDownloaded: " + bytesDownloaded + " size: " + size
            + " speed: " + speed;
    }
}
