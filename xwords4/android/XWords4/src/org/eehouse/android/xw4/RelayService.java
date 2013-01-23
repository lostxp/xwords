/* -*- compile-command: "cd ../../../../../; ant debug install"; -*- */
/*
 * Copyright 2010 - 2012 by Eric House (xwords@eehouse.org).  All
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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

import junit.framework.Assert;

import org.eehouse.android.xw4.jni.GameSummary;
import org.eehouse.android.xw4.jni.UtilCtxt;
import org.eehouse.android.xw4.MultiService.MultiEvent;

public class RelayService extends XWService {
    private static final int MAX_SEND = 1024;
    private static final int MAX_BUF = MAX_SEND - 2;

    private static final String CMD_STR = "CMD";
    private static final int UDP_CHANGED = 1;
    private static final int SEND = 2;
    private static final int RECEIVE = 3;
    
    private static final String ROWID = "ROWID";
    private static final String BINBUFFER = "BINBUFFER";

    private Thread m_fetchThread = null;
    private Thread m_UDPReadThread = null;
    private Thread m_UDPWriteThread = null;
    private DatagramSocket m_UDPSocket;
    private LinkedBlockingQueue<DatagramPacket> m_queue = null;

    // These must match the enum XWRelayReg in xwrelay.h
    private static final int XWPDEV_PROTO_VERSION = 0;
    // private static final int XWPDEV_NONE = 0;

    private enum XWRelayReg { 
             XWPDEV_NONE
            ,XWPDEV_ALERT
            ,XWPDEV_REG
            ,XWPDEV_REGRSP
            ,XWPDEV_PING
            ,XWPDEV_HAVEMSGS
            ,XWPDEV_RQSTMSGS
            ,XWPDEV_MSG
            ,XWPDEV_MSGNOCONN
            ,XWPDEV_MSGRSP
            ,XWPDEV_BADREG
            ,XWPDEV_ACK
            };

    // private static final int XWPDEV_ALERT = 1;
    // private static final int XWPDEV_REG = 2;
    // private static final int XWPDEV_REGRSP = 3;
    // private static final int XWPDEV_PING = 4;
    // private static final int XWPDEV_HAVEMSGS = 5;
    // private static final int XWPDEV_RQSTMSGS = 6;
    // private static final int XWPDEV_MSG = 7;
    // private static final int XWPDEV_MSGNOCONN = 8;
    // private static final int XWPDEV_MSGRSP = 9;
    // private static final int XWPDEV_BADREG = 10;

    public static void startService( Context context )
    {
        Intent intent = getIntentTo( context, UDP_CHANGED );
        context.startService( intent );
    }

    public static int sendPacket( Context context, long rowid, byte[] buf )
    {
        Intent intent = getIntentTo( context, SEND );
        intent.putExtra( ROWID, rowid );
        intent.putExtra( BINBUFFER, buf );
        context.startService( intent );
        return buf.length;
    }

    // Exists to get incoming data onto the main thread
    private static void postData( Context context, long rowid, byte[] msg )
    {
        DbgUtils.logf( "RelayService::postData: packet of length %d for token %d", 
                       msg.length, rowid );
        Intent intent = getIntentTo( context, RECEIVE );
        intent.putExtra( ROWID, rowid );
        intent.putExtra( BINBUFFER, msg );
        context.startService( intent );
    }

    public static void udpChanged( Context context )
    {
        startService( context );
    }

    private static Intent getIntentTo( Context context, int cmd )
    {
        Intent intent = new Intent( context, RelayService.class );
        intent.putExtra( CMD_STR, cmd );
        return intent;
    }

    @Override
    public void onCreate()
    {
        super.onCreate();
        startFetchThreadIf();
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId )
    {
        DbgUtils.logf( "RelayService::onStartCommand" );
        int result;
        if ( null != intent ) {
            int cmd = intent.getIntExtra( CMD_STR, -1 );
            switch( cmd ) {
            case -1:
                break;
            case UDP_CHANGED:
                DbgUtils.logf( "RelayService::onStartCommand::UDP_CHANGED" );
                if ( XWPrefs.getUDPEnabled( this ) ) {
                    stopFetchThreadIf();
                    startUDPThreads();
                    registerWithRelay();
                } else {
                    stopUDPThreadsIf();
                    startFetchThreadIf();
                }
                break;
            case SEND:
            case RECEIVE:
                long rowid = intent.getLongExtra( ROWID, -1 );
                byte[] msg = intent.getByteArrayExtra( BINBUFFER );
                if ( SEND == cmd ) {
                    sendMessage( rowid, msg );
                } else {
                    feedMessage( rowid, msg );
                }
                break;
            default:
                Assert.fail();
            }

            result = Service.START_STICKY;
        } else {
            result = Service.START_STICKY_COMPATIBILITY;
        }
        return result;
    }

    private void setupNotification( String[] relayIDs )
    {
        for ( String relayID : relayIDs ) {
            long[] rowids = DBUtils.getRowIDsFor( this, relayID );
            if ( null != rowids ) {
                for ( long rowid : rowids ) {
                    setupNotification( rowid );
                }
            }
        }
    }

    private void setupNotification( long rowid )
    {
        Intent intent = GamesList.makeRowidIntent( this, rowid );
        String msg = Utils.format( this, R.string.notify_bodyf, 
                                   GameUtils.getName( this, rowid ) );
        Utils.postNotification( this, intent, R.string.notify_title,
                                msg, (int)rowid );
    }
    
    private void startFetchThreadIf()
    {
        DbgUtils.logf( "startFetchThreadIf()" );
        if ( !XWPrefs.getUDPEnabled( this ) && null == m_fetchThread ) {
            m_fetchThread = new Thread( null, new Runnable() {
                    public void run() {
                        fetchAndProcess();
                        m_fetchThread = null;
                        RelayService.this.stopSelf();
                    }
                }, getClass().getName() );
            m_fetchThread.start();
        }
    }

    private void stopFetchThreadIf()
    {
        if ( null != m_fetchThread ) {
            DbgUtils.logf( "2: m_fetchThread NOT NULL; WHAT TO DO???" );
        }
    }

    private void startUDPThreads()
    {
        DbgUtils.logf( "startUDPThreads" );
        Assert.assertTrue( XWPrefs.getUDPEnabled( this ) );

        if ( null == m_UDPSocket ) {
            int port = XWPrefs.getDefaultRelayPort( RelayService.this );
            String host = XWPrefs.getDefaultRelayHost( RelayService.this );
            try { 
                m_UDPSocket = new DatagramSocket();
                InetAddress addr = InetAddress.getByName( host );
                m_UDPSocket.connect( addr, port ); // meaning: remember this address
            } catch( java.net.SocketException se ) {
                DbgUtils.loge( se );
                Assert.fail();
            } catch( java.net.UnknownHostException uhe ) {
                DbgUtils.loge( uhe );
            }
        } else {
            Assert.assertTrue( m_UDPSocket.isConnected() );
            DbgUtils.logf( "m_UDPSocket not null" );
        }

        if ( null == m_UDPReadThread ) {
            m_UDPReadThread = new Thread( null, new Runnable() {
                    public void run() {
                        DbgUtils.logf( "read thread running" );
                        byte[] buf = new byte[1024];
                        for ( ; ; ) {
                            DatagramPacket packet = 
                                new DatagramPacket( buf, buf.length );
                            try {
                                DbgUtils.logf( "UPD read thread blocking on receive" );
                                m_UDPSocket.receive( packet );
                                DbgUtils.logf( "UPD read thread: receive returned" );
                            } catch( java.io.IOException ioe ) {
                                DbgUtils.loge( ioe );
                                break; // ???
                            }
                            DbgUtils.logf( "received %d bytes", packet.getLength() );
                            gotPacket( packet );
                        }
                        DbgUtils.logf( "read thread exiting" );
                    }
                }, getClass().getName() );
            m_UDPReadThread.start();
        } else {
            DbgUtils.logf( "m_UDPReadThread not null and assumed to be running" );
        }

        if ( null == m_UDPWriteThread ) {
            m_queue = new LinkedBlockingQueue<DatagramPacket>();
            m_UDPWriteThread = new Thread( null, new Runnable() {
                    public void run() {
                        DbgUtils.logf( "write thread running" );
                        for ( ; ; ) {
                            DatagramPacket outPacket;
                            try {
                                outPacket = m_queue.take();
                            } catch ( InterruptedException ie ) {
                                DbgUtils.logf( "RelayService; write thread killed" );
                                break;
                            }
                            if ( null == outPacket || 0 == outPacket.getLength() ) {
                                DbgUtils.logf( "stopping write thread" );
                                break;
                            }
                            DbgUtils.logf( "Sending udp packet of length %d", 
                                           outPacket.getLength() );
                            try {
                                m_UDPSocket.send( outPacket );
                            } catch ( java.io.IOException ioe ) {
                                DbgUtils.loge( ioe );
                            }
                        }
                        DbgUtils.logf( "write thread exiting" );
                    }
                }, getClass().getName() );
            m_UDPWriteThread.start();
        } else {
            DbgUtils.logf( "m_UDPWriteThread not null and assumed to be running" );
        }
    }

    private void stopUDPThreadsIf()
    {
        DbgUtils.logf( "stopUDPThreadsIf" );
        if ( null != m_queue && null != m_UDPWriteThread ) {
            // can't add null
            m_queue.add( new DatagramPacket( new byte[0], 0 ) );
            try {
                DbgUtils.logf( "joining m_UDPWriteThread" );
                m_UDPWriteThread.join();
                DbgUtils.logf( "SUCCESSFULLY joined m_UDPWriteThread" );
            } catch( java.lang.InterruptedException ie ) {
                DbgUtils.loge( ie );
            }
            m_UDPWriteThread = null;
            m_queue = null;
        }
        if ( null != m_UDPSocket && null != m_UDPReadThread ) {
            m_UDPSocket.close();
            DbgUtils.logf( "waiting for read thread to exit" );
            try {
                m_UDPReadThread.join();
            } catch( java.lang.InterruptedException ie ) {
                DbgUtils.loge( ie );
            }
            DbgUtils.logf( "read thread exited" );
            m_UDPReadThread = null;
            m_UDPSocket = null;
        }
        DbgUtils.logf( "stopUDPThreadsIf DONE" );
    }

    // Running on reader thread
    private void gotPacket( DatagramPacket packet )
    {
        int packetLen = packet.getLength();
        byte[] data = new byte[packetLen];
        System.arraycopy( packet.getData(), 0, data, 0, packetLen );
        DbgUtils.logf( "RelayService::gotPacket: %d bytes of data", packetLen );
        ByteArrayInputStream bis = new ByteArrayInputStream( data );
        DataInputStream dis = new DataInputStream( bis );
        try {
            PacketHeader header = readHeader( dis );
            if ( null != header ) {
                sendAckIf( header );
                switch ( header.m_cmd ) { 
                case XWPDEV_ALERT:
                    String str = getStringWithLength( dis );
                    sendResult( MultiEvent.RELAY_ALERT, str );
                    break;
                case XWPDEV_BADREG:
                    str = getStringWithLength( dis );
                    DbgUtils.logf( "bad relayID \"%s\" reported", str );
                    XWPrefs.clearRelayDevID( this );
                    registerWithRelay();
                    break;
                case XWPDEV_REGRSP:
                    DbgUtils.logf( "got XWPDEV_REGRSP" );
                    str = getStringWithLength( dis );
                    DbgUtils.logf( "got relayid %s", str );
                    XWPrefs.setRelayDevID( this, str );
                    break;
                case XWPDEV_HAVEMSGS:
                    requestMessages();
                    break;
                case XWPDEV_MSG:
                    DbgUtils.logf( "got XWPDEV_MSG" );
                    int token = dis.readInt();
                    byte[] msg = new byte[dis.available()];
                    Assert.assertTrue( packet.getLength() >= msg.length );
                    Assert.assertTrue( packetLen >= msg.length );
                    dis.read( msg );
                    postData( RelayService.this, token, msg );
                    break;
                default:
                    DbgUtils.logf( "RelayService: Unhandled cmd: %d", 
                                   header.m_cmd );
                    break;
                }
            }
        } catch ( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
        }
    } // gotPacket

    private void registerWithRelay()
    {
        DbgUtils.logf( "registerWithRelay" );
        byte[] typ = new byte[1];
        String devid = getDevID(typ);

        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        try {
            DataOutputStream out = addProtoAndCmd( bas, XWRelayReg.XWPDEV_REG );
            out.writeByte( typ[0] );
            out.writeShort( devid.length() );
            out.writeBytes( devid );
            postPacket( bas );
        } catch ( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
        }
    }

    private void requestMessages()
    {
        DbgUtils.logf( "requestMessages" );
        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        try {
            DataOutputStream out = 
                addProtoAndCmd( bas, XWRelayReg.XWPDEV_RQSTMSGS );
            String devid = getDevID( null );
            out.writeShort( devid.length() );
            out.writeBytes( devid );
            postPacket( bas );
        } catch ( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
        }
    }

    private void sendMessage( long rowid, byte[] msg )
    {
        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        try {
            DataOutputStream out = addProtoAndCmd( bas, XWRelayReg.XWPDEV_MSG );
            Assert.assertTrue( rowid < Integer.MAX_VALUE );
            out.writeInt( (int)rowid );
            out.write( msg, 0, msg.length );
            postPacket( bas );
        } catch ( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
        } 
    }

    private void sendNoConnMessage( long rowid, String relayID, byte[] msg )
    {
        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        try {
            DataOutputStream out = 
                addProtoAndCmd( bas, XWRelayReg.XWPDEV_MSGNOCONN );
            Assert.assertTrue( rowid < Integer.MAX_VALUE );
            out.writeInt( (int)rowid );
            out.writeBytes( relayID );
            out.write( '\n' );
            out.write( msg, 0, msg.length );
            postPacket( bas );
        } catch ( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
        } 
    }

    private void sendAckIf( PacketHeader header )
    {
        DbgUtils.logf( "sendAckIf" );
        if ( XWRelayReg.XWPDEV_ACK != header.m_cmd ) {
            ByteArrayOutputStream bas = new ByteArrayOutputStream();
            try {
                DataOutputStream out = 
                    addProtoAndCmd( bas, XWRelayReg.XWPDEV_ACK );
                out.writeInt( header.m_packetID );
                postPacket( bas );
            } catch ( java.io.IOException ioe ) {
                DbgUtils.loge( ioe );
            }
        }
    }

    private PacketHeader readHeader( DataInputStream dis )
        throws java.io.IOException
    {
        PacketHeader result = null;
        byte proto = dis.readByte();
        if ( XWPDEV_PROTO_VERSION == proto ) {
            int packetID = dis.readInt();
            DbgUtils.logf( "readHeader: got packetID %d", packetID );
            byte ordinal = dis.readByte();
            XWRelayReg cmd = XWRelayReg.values()[ordinal];
            result = new PacketHeader( cmd, packetID );
        } else {
            DbgUtils.logf( "bad proto: %d", proto );
        }
        DbgUtils.logf( "readHeader => %H", result );
        return result;
    }

    private String getStringWithLength( DataInputStream dis )
        throws java.io.IOException
    {
        short len = dis.readShort();
        byte[] tmp = new byte[len];
        dis.read( tmp );
        return new String( tmp );
    }

    private DataOutputStream addProtoAndCmd( ByteArrayOutputStream bas, 
                                             XWRelayReg cmd )
        throws java.io.IOException
    {
        DataOutputStream out = new DataOutputStream( bas );
        out.writeByte( XWPDEV_PROTO_VERSION );
        out.writeInt( 0 );    // packetID
        out.writeByte( cmd.ordinal() );
        return out;
    }

    private void postPacket( ByteArrayOutputStream bas )
    {
        byte[] data = bas.toByteArray();
        m_queue.add( new DatagramPacket( data, data.length ) );
    }

    private String getDevID( byte[] typp )
    {
        byte typ;
        String devid = XWPrefs.getRelayDevID( this );
        if ( null != devid && 0 < devid.length() ) {
            typ = UtilCtxt.ID_TYPE_RELAY;
        } else {
            devid = XWPrefs.getGCMDevID( this );
            if ( null != devid && 0 < devid.length() ) {
                typ = UtilCtxt.ID_TYPE_ANDROID_GCM;
            } else {
                devid = "DO NOT SHIP WITH ME";
                typ = UtilCtxt.ID_TYPE_ANDROID_OTHER;
            }
        }
        if ( null != typp ) {
            typp[0] = typ;
        } else {
            Assert.assertTrue( typ == UtilCtxt.ID_TYPE_RELAY );
        }
        return devid;
    }

    private void feedMessage( long rowid, byte[] msg )
    {
        DbgUtils.logf( "RelayService::feedMessage: %d bytes for rowid %d", 
                       msg.length, rowid );
        if ( BoardActivity.feedMessage( rowid, msg ) ) {
            DbgUtils.logf( "feedMessage: board ate it" );
            // do nothing
        } else {
            RelayMsgSink sink = new RelayMsgSink();
            sink.setRowID( rowid );
            if ( GameUtils.feedMessage( this, rowid, msg, null, 
                                        sink ) ) {
                setupNotification( rowid );
            } else {
                DbgUtils.logf( "feedMessage: background dropped it" );
            }
        }
    }

    private void fetchAndProcess()
    {
        long[][] rowIDss = new long[1][];
        String[] relayIDs = DBUtils.getRelayIDs( this, rowIDss );
        if ( null != relayIDs && 0 < relayIDs.length ) {
            long[] rowIDs = rowIDss[0];
            byte[][][] msgs = NetUtils.queryRelay( this, relayIDs );

            if ( null != msgs ) {
                RelayMsgSink sink = new RelayMsgSink();
                int nameCount = relayIDs.length;
                ArrayList<String> idsWMsgs =
                    new ArrayList<String>( nameCount );
                for ( int ii = 0; ii < nameCount; ++ii ) {
                    byte[][] forOne = msgs[ii];
                    // if game has messages, open it and feed 'em
                    // to it.
                    if ( null == forOne ) {
                        // Nothing for this relayID
                    } else if ( BoardActivity.feedMessages( rowIDs[ii], forOne )
                                || GameUtils.feedMessages( this, rowIDs[ii],
                                                           forOne, null,
                                                           sink ) ) {
                        idsWMsgs.add( relayIDs[ii] );
                    } else {
                        DbgUtils.logf( "dropping message for %s (rowid %d)",
                                       relayIDs[ii], rowIDs[ii] );
                    }
                }
                if ( 0 < idsWMsgs.size() ) {
                    String[] tmp = new String[idsWMsgs.size()];
                    idsWMsgs.toArray( tmp );
                    setupNotification( tmp );
                }
                sink.send( this );
            }
        }
    }

    private static void sendToRelay( Context context,
                                     HashMap<String,ArrayList<byte[]>> msgHash )
    {
        // format: total msg lenth: 2
        //         number-of-relayIDs: 2
        //         for-each-relayid: relayid + '\n': varies
        //                           message count: 1
        //                           for-each-message: length: 2
        //                                             message: varies

        if ( null != msgHash ) {
            try {
                // Build up a buffer containing everything but the total
                // message length and number of relayIDs in the message.
                ByteArrayOutputStream store = 
                    new ByteArrayOutputStream( MAX_BUF ); // mem
                DataOutputStream outBuf = new DataOutputStream( store );
                int msgLen = 4;          // relayID count + protocol stuff
                int nRelayIDs = 0;
        
                Iterator<String> iter = msgHash.keySet().iterator();
                while ( iter.hasNext() ) {
                    String relayID = iter.next();
                    int thisLen = 1 + relayID.length(); // string and '\n'
                    thisLen += 2;                        // message count

                    ArrayList<byte[]> msgs = msgHash.get( relayID );
                    for ( byte[] msg : msgs ) {
                        thisLen += 2 + msg.length;
                    }

                    if ( msgLen + thisLen > MAX_BUF ) {
                        // Need to deal with this case by sending multiple
                        // packets.  It WILL happen.
                        break;
                    }
                    // got space; now write it
                    ++nRelayIDs;
                    outBuf.writeBytes( relayID );
                    outBuf.write( '\n' );
                    outBuf.writeShort( msgs.size() );
                    for ( byte[] msg : msgs ) {
                        outBuf.writeShort( msg.length );
                        outBuf.write( msg );
                    }
                    msgLen += thisLen;
                }

                // Now open a real socket, write size and proto, and
                // copy in the formatted buffer
                Socket socket = NetUtils.makeProxySocket( context, 8000 );
                if ( null != socket ) {
                    DataOutputStream outStream = 
                        new DataOutputStream( socket.getOutputStream() );
                    outStream.writeShort( msgLen );
                    outStream.writeByte( NetUtils.PROTOCOL_VERSION );
                    outStream.writeByte( NetUtils.PRX_PUT_MSGS );
                    outStream.writeShort( nRelayIDs );
                    outStream.write( store.toByteArray() );
                    outStream.flush();
                    socket.close();
                }
            } catch ( java.io.IOException ioe ) {
                DbgUtils.loge( ioe );
            }
        } else {
            DbgUtils.logf( "sendToRelay: null msgs" );
        }
    } // sendToRelay

    private class RelayMsgSink extends MultiMsgSink {

        private HashMap<String,ArrayList<byte[]>> m_msgLists = null;
        private long m_rowid = -1;

        public void setRowID( long rowid ) { m_rowid = rowid; }

        public void send( Context context )
        {
            if ( -1 == m_rowid ) {
                sendToRelay( context, m_msgLists );
            } else {
                Assert.assertNull( m_msgLists );
            }
        }

        /***** TransportProcs interface *****/

        public boolean relayNoConnProc( byte[] buf, String relayID )
        {
            if ( -1 != m_rowid ) {
                sendNoConnMessage( m_rowid, relayID, buf );
            } else {
                if ( null == m_msgLists ) {
                    m_msgLists = new HashMap<String,ArrayList<byte[]>>();
                }

                ArrayList<byte[]> list = m_msgLists.get( relayID );
                if ( list == null ) {
                    list = new ArrayList<byte[]>();
                    m_msgLists.put( relayID, list );
                }
                list.add( buf );
            }
            return true;
        }
    }

    private class PacketHeader {
        public int m_packetID;
        public XWRelayReg m_cmd;
        public PacketHeader( XWRelayReg cmd, int packetID ) {
            DbgUtils.logf( "in PacketHeader contructor" );
            m_packetID = packetID;
            m_cmd = cmd;
        }
    }

}
