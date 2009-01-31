/* -*- compile-command: "make -j TARGET_OS=win32 DEBUG=TRUE" -*- */
/* 
 * Copyright 2005-2009 by Eric House (xwords@eehouse.org).  All rights
 * reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
#ifndef XWFEATURE_STANDALONE_ONLY

#include <winsock2.h>
#include <stdio.h>

#include "cesockwr.h"
#include "cemain.h"
#include "cedebug.h"
#include "debhacks.h"


/* This object owns all network activity: sending and receiving packets.  It
   maintains two threads, one to send and the other to listen.  Incoming
   packets are passed out via a proc passed into the "constructor".  Outgoing
   packets are passed in directly.  Uses TCP, and the relay framing protocol
   wherein each packet is proceeded by its length in two bytes, network byte
   order.
*/

enum { WRITER_THREAD, 
       READER_THREAD,
       N_THREADS };

typedef enum {
    CE_IPST_START
    ,CE_IPST_RESOLVINGHOST
    ,CE_IPST_HOSTRESOLVED
    ,CE_IPST_CONNECTING
    ,CE_IPST_CONNECTED
} CeConnState;

#define MAX_QUEUE_SIZE 3

struct CeSocketWrapper {
    WSADATA wsaData;
    DataRecvProc dataProc;
    CEAppGlobals* globals;

    union {
        HOSTENT hent;
        XP_U8 hostNameBuf[MAXGETHOSTSTRUCT];
    } hostNameUnion;
    HANDLE getHostTask;

    /* Outgoing queue */
    XP_U8* packets[MAX_QUEUE_SIZE];
    XP_U16 lens[MAX_QUEUE_SIZE];
    XP_U16 nPackets;

    /* Incoming */
    char in_buf[512];           /* char is what WSARecv wants */
    XP_U16 in_offset;

    CommsAddrRec addrRec;

    SOCKET socket;
    CeConnState connState;

    HANDLE queueMutex;

#ifdef DEBUG
    XP_U16 nSent;
#endif

    MPSLOT
};

#ifdef DEBUG
static const char*
ConnState2Str( CeConnState connState )
{
#define CASESTR(s)   case (s): return #s
    switch( connState ) {
        CASESTR( CE_IPST_START );
        CASESTR( CE_IPST_RESOLVINGHOST );
        CASESTR( CE_IPST_HOSTRESOLVED );
        CASESTR( CE_IPST_CONNECTING );
        CASESTR( CE_IPST_CONNECTED );
    }
#undef CASESTR
    return "<unknown>";
}
#else
# define ConnState2Str(s)
#endif

static XP_Bool connectIfNot( CeSocketWrapper* self );


/* queue_packet: Place packet on queue using semaphore.  Return false
 * if no room or fail for some other reason.
 */
static XP_Bool
queue_packet( CeSocketWrapper* self, XP_U8* packet, XP_U16 len )
{
    DWORD wres;
    XP_Bool success = XP_FALSE;

    // 2/5 second time-out interval.  This is called from the UI thread, so
    // long pauses are unacceptable.  comms will have to try again if for
    // some reason the queue is locked for that long.
    wres = WaitForSingleObject( self->queueMutex, 200L );

    if ( wres == WAIT_OBJECT_0 ) {
        if ( self->nPackets < MAX_QUEUE_SIZE - 1 ) {
            /* add it to the queue */
            self->packets[self->nPackets] = packet;
            self->lens[self->nPackets] = len;
            ++self->nPackets;
            XP_LOGF( "%s: there are now %d packets on send queue", 
                     __func__, self->nPackets );

            /* signal the writer thread */
/*             XP_LOGF( "%s: calling SetEvent(%p)", __func__, self->queueAddEvent ); */
/*             SetEvent( self->queueAddEvent ); */
/*             success = XP_TRUE; */
        }

        if ( !ReleaseMutex( self->queueMutex ) ) {
            logLastError( "ReleaseMutex" );
        }
    } else {
        XP_LOGF( "timed out" );
    }

    return success;
}

static XP_Bool
get_packet( CeSocketWrapper* self, XP_U8** packet, XP_U16* len )
{
    DWORD wres = WaitForSingleObject( self->queueMutex, INFINITE );
    XP_Bool success = wres == WAIT_OBJECT_0;
    
    if ( success ) {
        success = self->nPackets > 0;
        if ( success ) {
            *packet = self->packets[0];
            *len = self->lens[0];
        }
        if ( !ReleaseMutex( self->queueMutex ) ) {
            logLastError( "ReleaseMutex" );
        }
    }

    return success;
} /* get_packet */

/* called by WriterThreadProc */
static void
remove_packet( CeSocketWrapper* self )
{
    DWORD wres = WaitForSingleObject( self->queueMutex, INFINITE );
    if ( wres == WAIT_OBJECT_0 ) {
        XP_ASSERT( self->nPackets > 0 );
        if ( --self->nPackets > 0 ) {
            XP_MEMCPY( &self->packets[0], &self->packets[1],
                       self->nPackets * sizeof(self->packets[0]) );
            XP_MEMCPY( &self->lens[0], &self->lens[1],
                       self->nPackets * sizeof(self->lens[0]) );
        } else {
            XP_ASSERT( self->nPackets == 0 );
        }
        if ( !ReleaseMutex( self->queueMutex ) ) {
            logLastError( "ReleaseMutex" );
        }
    }
    XP_LOGF( "%d packets left on queue", self->nPackets );
} /* remove_packet */

static XP_Bool
sendAll( CeSocketWrapper* self, XP_U8* buf, XP_U16 len )
{
    for ( ; ; ) {
        int nSent = send( self->socket, (char*)buf, len, 0 ); /* flags? */
        if ( nSent == SOCKET_ERROR ) {
            return XP_FALSE;
        } else if ( nSent == len ) {
            XP_LOGF( "sent %d bytes", nSent );
            return XP_TRUE;
        } else {
            XP_LOGF( "sent %d bytes", nSent );
            XP_ASSERT( nSent < len );
            len -= nSent;
            buf += nSent;
        }
    }
} /* sendAll */

static XP_Bool
sendLenAndData( CeSocketWrapper* self, XP_U8* packet, XP_U16 len )
{
    XP_Bool success;
    XP_U16 lenData;
    XP_ASSERT( self->socket != -1 );

    lenData = XP_HTONS( len );
    success = sendAll( self, (XP_U8*)&lenData, sizeof(lenData) )
        && sendAll( self, packet, len );
    return success;
} /* sendLenAndData */

static void
send_packet_if( CeSocketWrapper* self )
{
    XP_U8* packet;
    XP_U16 len;
    if ( get_packet( self, &packet, &len ) ) {
        if ( sendLenAndData( self, packet, len ) ) {
            /* successful send.  Remove our copy */
            remove_packet( self );
            XP_FREE( self->mpool, packet );
        }
    }
}

static void
stateChanged( CeSocketWrapper* self, CeConnState newState )
{
    CeConnState curState = self->connState;
    self->connState = newState;

    XP_LOGF( "%s: %s -> %s", __func__, ConnState2Str( curState ), 
             ConnState2Str( newState ) );

    switch( newState ) {
    case CE_IPST_START:
        break;
    case CE_IPST_RESOLVINGHOST:
        break;
    case CE_IPST_HOSTRESOLVED:
        connectIfNot( self );
        break;
    case CE_IPST_CONNECTING:
        break;
    case CE_IPST_CONNECTED:
        send_packet_if( self );
        break;
    }
    
}

static XP_Bool
connectSocket( CeSocketWrapper* self )
{
    SOCKET sock;

    if ( self->addrRec.u.ip_relay.ipAddr != 0 ) {
        sock = WSASocket( AF_INET, SOCK_STREAM, IPPROTO_IP, 
                          NULL, 0, WSA_FLAG_OVERLAPPED );
        XP_LOGF( "got socket %d", sock );

        if ( sock != INVALID_SOCKET ) {
            struct sockaddr_in name = {0};

            /* Put socket in non-blocking mode */
            if ( 0 != WSAAsyncSelect( sock, self->globals->hWnd,
                                      XWWM_SOCKET_EVT,
                                      FD_READ | FD_WRITE | FD_CONNECT ) ) {
                XP_WARNF( "WSAAsyncSelect failed" );
            }

            name.sin_family = AF_INET;
            name.sin_port = XP_HTONS( self->addrRec.u.ip_relay.port );
            name.sin_addr.S_un.S_addr = XP_HTONL(self->addrRec.u.ip_relay.ipAddr);

            XP_LOGF( "%s: calling WSAConnect", __func__ );
            if ( SOCKET_ERROR != WSAConnect( sock, (struct sockaddr *)&name, 
                                             sizeof(name), NULL, NULL, 
                                             NULL, NULL ) ) {
                self->socket = sock;
                stateChanged( self, CE_IPST_CONNECTED );
            } else if ( WSAEWOULDBLOCK == WSAGetLastError() ) {
                stateChanged( self, CE_IPST_CONNECTING );
            } else {
                int err = WSAGetLastError();
                XP_LOGF( "%s:%d: WSAGetLastError=>%d", __func__, __LINE__, err );
            }
        } else {
            int err = WSAGetLastError();
            XP_LOGF( "%s:%d: WSAGetLastError=>%d", __func__, __LINE__, err );
        }
    }

    XP_LOGF( "%d", self->connState == CE_IPST_CONNECTED );
    return self->connState == CE_IPST_CONNECTED;
} /* connectSocket */

static XP_Bool
connectIfNot( CeSocketWrapper* self )
{
    LOG_FUNC();
    XP_Bool success = self->connState == CE_IPST_CONNECTED;

    if ( !success && CE_IPST_HOSTRESOLVED == self->connState ) {
        success = connectSocket( self );
    }
    return success;
} /* connectIfNot */

static void
closeConnection( CeSocketWrapper* self )
{
    if ( self->connState >= CE_IPST_CONNECTED ) {

        if ( self->socket != -1 ) {
            MS(closesocket)( self->socket );
        }

        self->socket = -1;
        stateChanged( self, CE_IPST_START );
    }
} /* closeConnection */

#if 0
static DWORD
WriterThreadProc( LPVOID lpParameter )
{
    CeSocketWrapper* self = (CeSocketWrapper*)lpParameter;

    connectSocket( self );

    /* Then loop waiting for packets to write to it. */
    for ( ; ; ) { 
        XP_U8* packet;
        XP_U16 len;

        WaitForSingleObject( self->queueAddEvent, INFINITE );

        if ( get_packet( self, &packet, &len ) && connectIfNot( self ) ) {
            if ( sendLenAndData( self, packet, len ) ) {

                /* successful send.  Remove our copy */
                remove_packet( self );
                XP_FREE( self->mpool, packet );
            }
        }

        /* Should this happen sooner?  What if other thread signals in the
           meantime? */
        ResetEvent( self->queueAddEvent );
    }

    ExitThread(0);              /* docs say to exit this way */
    return 0;
} /* WriterThreadProc */
#endif

#if 0
/* Read until we get the number of bytes sought or until an error's
   received. */
static XP_Bool
read_bytes_blocking( CeSocketWrapper* self, XP_U8* buf, XP_U16 len )
{
    while ( len > 0 ) {
        fd_set readSet;
        int sres;

        FD_ZERO( &readSet );
        /* There also needs to be a pipe in here for interrupting */
        FD_SET( self->socket, &readSet );

        sres = MS(select)( 0,   /* nFds is ignored on wince */
                           &readSet, NULL, NULL, /* others not interesting */
                           NULL ); /* no timeout */
        XP_LOGF( "back from select: got %d", sres );
        if ( sres == 0 ) {
            break;
        } else if ( sres == 1 && FD_ISSET( self->socket, &readSet ) ) {
            int nRead = MS(recv)( self->socket, (char*)buf, len, 0 );
            if ( nRead > 0 ) {
                XP_LOGF( "read %d bytes", nRead );
                XP_ASSERT( nRead <= len );
                buf += nRead;
                len -= nRead;
            } else {
                break;
            }
        } else {
            XP_ASSERT(0);
            break;
        }
    }

    /* We probably want to close the socket if something's wrong here.  Once
       we get out of sync somehow we'll never get the framing right again. */
    XP_ASSERT( len == 0 );
    return len == 0;
} /* read_bytes_blocking */
#endif

#if 0
static DWORD
ReaderThreadProc( LPVOID lpParameter )
{
    XP_U8 buf[MAX_MSG_LEN];
    CeSocketWrapper* self = (CeSocketWrapper*)lpParameter;

    for ( ; ; ) {
        WaitForSingleObject( self->socketConnEvent, INFINITE );

        for ( ; ; ) {
            XP_U16 len;
            XP_LOGF( "ReaderThreadProc running" );

            /* This will block in select */
            if ( !read_bytes_blocking( self, (XP_U8*)&len, sizeof(len) ) ) {
                break;          /* bad socket.  Go back to waiting new
                                   one. */
            }
            len = XP_NTOHS( len );
            if ( !read_bytes_blocking( self, buf, len ) ) {
                break;          /* bad socket */
            }

            (*self->dataProc)( buf, len, self->globals );
        }
    }

    ExitThread(0);              /* docs say to exit this way */
    return 0;
} /* ReaderThreadProc */
#endif

static void
getHostAddr( CeSocketWrapper* self )
{
    if ( self->addrRec.u.ip_relay.hostName[0] ) {
        XP_LOGF( "%s: calling WSAAsyncGetHostByName(%s)", 
                 __func__, self->addrRec.u.ip_relay.hostName );
        self->getHostTask
            = WSAAsyncGetHostByName( self->globals->hWnd,
                                     XWWM_HOSTNAME_ARRIVED,
                                     self->addrRec.u.ip_relay.hostName,
                                     (char*)&self->hostNameUnion,
                                     sizeof(self->hostNameUnion) );
        if ( NULL == self->getHostTask ) {
            int err = WSAGetLastError();
            XP_LOGF( "%s: WSAGetLastError=>%d", __func__, err );
        }

        stateChanged( self, CE_IPST_RESOLVINGHOST );
    }
}

CeSocketWrapper* 
ce_sockwrap_new( MPFORMAL DataRecvProc proc, CEAppGlobals* globals )
{
    CeSocketWrapper* self = NULL;

    WSADATA wsaData;
    int iResult = WSAStartup(MAKEWORD(2,2), &wsaData);
    if (iResult != NO_ERROR) {
        XP_WARNF("Error at WSAStartup()\n");
    } else {

        self = XP_MALLOC( mpool, sizeof(*self) );
        XP_MEMSET( self, 0, sizeof(*self) );

        self->wsaData = wsaData;

        self->dataProc = proc;
        self->globals = globals;
        MPASSIGN(self->mpool, mpool );
        self->socket = -1;

        self->queueMutex = CreateMutex( NULL, FALSE, NULL );
        XP_ASSERT( self->queueMutex != NULL );

        getHostAddr( self );
    }
    return self;
} /* ce_sockwrap_new */

void
ce_sockwrap_delete( CeSocketWrapper* self )
{
    /* This isn't a good thing to do.  Better to signal them to exit
       some other way */
    closeConnection( self );

    CloseHandle( self->queueMutex );

    WSACleanup();

    XP_FREE( self->mpool, self );
} /* ce_sockwrap_delete */

void
ce_sockwrap_hostname( CeSocketWrapper* self, WPARAM wParam, LPARAM lParam )
{
    LOG_FUNC();
    DWORD err = WSAGETASYNCERROR( lParam );

    XP_ASSERT( CE_IPST_RESOLVINGHOST == self->connState );

    if ( 0 == err ) {
        HANDLE comp = (HANDLE)wParam;
        XP_ASSERT( comp == self->getHostTask );

        XP_U32 tmp;
        XP_MEMCPY( &tmp, &self->hostNameUnion.hent.h_addr_list[0][0], 
                   sizeof(tmp) );
        self->addrRec.u.ip_relay.ipAddr = XP_NTOHL( tmp );

        XP_LOGF( "got address: %d.%d.%d.%d", 
                 (int)((tmp>>0) & 0xFF), 
                 (int)((tmp>>8) & 0xFF), 
                 (int)((tmp>>16) & 0xFF), 
                 (int)((tmp>>24) & 0xFF) );

        stateChanged( self, CE_IPST_HOSTRESOLVED );
    } else {
        XP_LOGF( "%s: async operation failed: %ld", __func__, err );
/* WSAENETDOWN */
/* WSAENOBUFS */
/* WSAEFAULT */
/* WSAHOST_NOT_FOUND */
/* WSATRY_AGAIN */
/* WSANO_RECOVERY */
/* WSANO_DATA */
    }

    LOG_RETURN_VOID();
} /* ce_sockwrap_hostname */

/* MSDN: When one of the nominated network events occurs on the specified
   socket s, the application window hWnd receives message wMsg. The wParam
   parameter identifies the socket on which a network event has occurred. The
   low word of lParam specifies the network event that has occurred. The high
   word of lParam contains any error code. The error code be any error as
   defined in Winsock2.h.
 */

static XP_Bool
dispatch_if_complete( CeSocketWrapper* self, XP_U16 nBytesRecvd )
{
    XP_U16 lenInBuffer = nBytesRecvd + self->in_offset;
    XP_U16 msgLen;
    XP_Bool draw = XP_FALSE;
    if ( lenInBuffer >= sizeof(msgLen) ) {
        XP_MEMCPY( &msgLen, self->in_buf, sizeof(msgLen) );
        msgLen = XP_NTOHS( msgLen );

        XP_LOGF( "%s: at least we have len: %d", __func__, msgLen );

        /* We know the length of the full buffer.  Do we have it? */
        if ( lenInBuffer >= (msgLen + sizeof(msgLen)) ) {
            XP_U16 lenLeft, lenUsed;

            /* first send */
            XP_LOGF( "%s: sending %d bytes to dataProc", __func__, msgLen );
            draw = (*self->dataProc)( (XP_U8*)&self->in_buf[sizeof(msgLen)], 
                                      msgLen, self->globals );

            /* then move down any additional bytes */
            lenUsed = msgLen + sizeof(msgLen);
            XP_ASSERT( lenInBuffer >= lenUsed );
            lenLeft = lenInBuffer - lenUsed;
            if ( lenLeft > 0 ) {
                XP_MEMCPY( self->in_buf, &self->in_buf[lenUsed], lenLeft );
            }

            self->in_offset = 0;
            nBytesRecvd = lenLeft; /* will set below */
        }
    }

    self->in_offset += nBytesRecvd;
    return draw;
} /* dispatch_if_complete */

static XP_U16
read_from_socket( CeSocketWrapper* self )
{
    WSABUF wsabuf;
    DWORD flags = 0;
    DWORD nBytesRecvd = 0;

    wsabuf.buf = &self->in_buf[self->in_offset];
    wsabuf.len = sizeof(self->in_buf) - self->in_offset;

    int err = WSARecv( self->socket, &wsabuf, 1, &nBytesRecvd, 
                       &flags, NULL, NULL );
    if ( 0 == err ) {
        XP_LOGF( "%s: got %ld bytes", __func__, nBytesRecvd );
    } else {
        XP_ASSERT( err == SOCKET_ERROR );
        err = WSAGetLastError();
        XP_LOGF( "%s: WSARecv=>%d", __func__, err );
    }

    XP_ASSERT( nBytesRecvd < 0xFFFF );
    return (XP_U16)nBytesRecvd;
} /* read_from_socket */

XP_Bool
ce_sockwrap_event( CeSocketWrapper* self, WPARAM wParam, LPARAM lParam )
{
    SOCKET socket = (SOCKET)wParam;
    long event = (long)LOWORD(lParam);
    XP_Bool draw = XP_FALSE;

    if ( 0 != (FD_READ & event) ) {
        XP_U16 nReceived;
        XP_LOGF( "%s: got FD_READ", __func__ );
        nReceived = read_from_socket( self );
        if ( nReceived > 0 ) {
            draw = dispatch_if_complete( self, nReceived );
        }
        event &= ~FD_READ;
    }
    if ( 0 != (FD_WRITE & event) ) {
        event &= ~FD_WRITE;
        XP_LOGF( "%s: got FD_WRITE", __func__ );
    }
    if ( 0 != (FD_CONNECT & event) ) {
        XP_LOGF( "%s: got FD_CONNECT", __func__ );
        event &= ~FD_CONNECT;
        self->socket = socket;
        stateChanged( self, CE_IPST_CONNECTED );
    }

    if ( 0 != event ) {
        XP_WARNF( "%s: unexpected bits left: 0x%lx", __func__, event );
    }
    return draw;
}

XP_U16
ce_sockwrap_send( CeSocketWrapper* self, const XP_U8* buf, XP_U16 len, 
                  const CommsAddrRec* addr )
{
    XP_U8* packet;

    /* If the address has changed, we need to close the connection.  Send
       thread will take care of opening it again. */
    XP_ASSERT( addr->conType == COMMS_CONN_RELAY );
    if ( 0 != XP_STRCMP( addr->u.ip_relay.hostName, 
                         self->addrRec.u.ip_relay.hostName )
         || 0 != XP_STRCMP( addr->u.ip_relay.cookie, 
                            self->addrRec.u.ip_relay.cookie )
         || addr->u.ip_relay.port != self->addrRec.u.ip_relay.port ) {
        closeConnection( self );
        XP_MEMCPY( &self->addrRec, addr, sizeof(self->addrRec) );

        getHostAddr( self );
    }

    packet = XP_MALLOC( self->mpool, len );
    XP_MEMCPY( packet, buf, len );
    if ( !queue_packet( self, packet, len ) ) {
        len = 0;                /* error */
    }

    return len;
} /* ce_sockwrap_send */

#endif
