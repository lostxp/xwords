/* -*- compile-command: "cd ../../../../../; ant install"; -*- */
/*
 * Copyright 2009-2010 by Eric House (xwords@eehouse.org).  All
 * rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.eehouse.android.xw4;

import javax.net.SocketFactory;
import java.net.InetAddress;
import java.net.Socket;
import android.content.Context;

import java.io.InputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;

import org.eehouse.android.xw4.jni.CommonPrefs;

public class NetUtils {

    public static final byte PROTOCOL_VERSION = 0;
    // from xwrelay.h
    public static byte PRX_PUB_ROOMS = 1;
    public static byte PRX_HAS_MSGS = 2;
    public static byte PRX_DEVICE_GONE = 3;

    public static Socket MakeProxySocket( Context context, 
                                          int timeoutMillis )
    {
        Socket socket = null;
        try {
            int port = CommonPrefs.getDefaultProxyPort( context );
            String host = CommonPrefs.getDefaultRelayHost( context );

            SocketFactory factory = SocketFactory.getDefault();
            InetAddress addr = InetAddress.getByName( host );
            socket = factory.createSocket( addr, port );
            socket.setSoTimeout( timeoutMillis );

        } catch ( java.net.UnknownHostException uhe ) {
            Utils.logf( uhe.toString() );
        } catch( java.io.IOException ioe ) {
            Utils.logf( ioe.toString() );
        }
        Utils.logf( "MakeProxySocket=>%s", null != socket
                    ? socket.toString():"null" );
        return socket;
    }

    private static class InformThread extends Thread {
        private Socket m_socket;
        private String m_relayID;
        private int m_seed;
        public InformThread( Socket socket, String relayID, int seed )
        {
            m_socket = socket;
            m_relayID = relayID;
            m_seed = seed;
        }
        public void run() {
            try {
                DataOutputStream outStream = 
                    new DataOutputStream( m_socket.getOutputStream() );
                outStream.writeShort( 2 + 2 + 2 + m_relayID.length() + 1 );
                outStream.writeByte( NetUtils.PROTOCOL_VERSION );
                outStream.writeByte( NetUtils.PRX_DEVICE_GONE );
                outStream.writeShort( 1 ); // only one id for now
                outStream.writeShort( m_seed );
                outStream.writeBytes( m_relayID );
                outStream.write( '\n' );
                outStream.flush();

                DataInputStream dis = 
                    new DataInputStream( m_socket.getInputStream() );
                short resLen = dis.readShort();
                m_socket.close();
            } catch ( java.io.IOException ioe ) {
                Utils.logf( ioe.toString() );
            }
        }
    }

    public static void informOfDeath( Context context, String relayID, 
                                      int seed )
    {
        Socket socket = MakeProxySocket( context, 10000 );
        InformThread thread = new InformThread( socket, relayID, seed );
        thread.start();
    }

    public static String[] QueryRelay( Context context )
    {
        String[] result = null;
        int[] nBytes = new int[1];
        String[] ids = collectIDs( context, nBytes );
        if ( null != ids && 0 < ids.length ) {
            try {
                Socket socket = MakeProxySocket( context, 8000 );
                DataOutputStream outStream = 
                    new DataOutputStream( socket.getOutputStream() );

                // total packet size
                outStream.writeShort( 2 + nBytes[0] + ids.length + 1 );
                Utils.logf( "total packet size: %d",
                            2 + nBytes[0] + ids.length );

                outStream.writeByte( NetUtils.PROTOCOL_VERSION );
                outStream.writeByte( NetUtils.PRX_HAS_MSGS );

                // number of ids
                outStream.writeShort( ids.length );
                Utils.logf( "wrote count %d to proxy socket",
                            ids.length );

                for ( String id : ids ) {
                    outStream.writeBytes( id );
                    outStream.write( '\n' );
                }
                outStream.flush();

                DataInputStream dis = 
                    new DataInputStream(socket.getInputStream());
                Utils.logf( "reading from proxy socket" );
                short resLen = dis.readShort();
                short nameCount = dis.readShort();
                short[] msgCounts = null;
                if ( nameCount == ids.length ) {
                    msgCounts = new short[nameCount];
                    for ( int ii = 0; ii < nameCount; ++ii ) {
                        msgCounts[ii] = dis.readShort();
                        Utils.logf( "msgCounts[%d]=%d", ii, 
                                    msgCounts[ii] );
                    }
                }
                socket.close();
                Utils.logf( "closed proxy socket" );

                if ( null == msgCounts ) {
                    Utils.logf( "relay has no messages" );
                } else {
                    ArrayList<String> idsWMsgs =
                        new ArrayList<String>( nameCount );
                    for ( int ii = 0; ii < nameCount; ++ii ) {
                        if ( msgCounts[ii] > 0 ) {
                            String msg = 
                                String.format("%d messages for %s",
                                              msgCounts[ii], 
                                              ids[ii] );
                            Utils.logf( msg );
                            DBUtils.setHasMsgs( ids[ii] );
                            idsWMsgs.add( ids[ii] );
                        }
                    }
                    if ( 0 < idsWMsgs.size() ) {
                        ids = new String[idsWMsgs.size()];
                        result = idsWMsgs.toArray( ids );
                    }
                }

            } catch( java.net.UnknownHostException uhe ) {
                Utils.logf( uhe.toString() );
            } catch( java.io.IOException ioe ) {
                Utils.logf( ioe.toString() );
            } catch( NullPointerException npe ) {
                Utils.logf( npe.toString() );
            }
        }
        return result;
    }

    private static String[] collectIDs( Context context, int[] nBytes )
    {
        String[] ids = DBUtils.getRelayIDNoMsgs( context );
        int len = 0;
        if ( null != ids ) {
            for ( String id : ids ) {
                Utils.logf( "got relayID: %s", id );
                len += id.length();
            }
        }
        nBytes[0] = len;
        return ids;
    }
}
