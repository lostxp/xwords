/* -*- compile-command: "make -j3"; -*- */
/* 
 * Copyright 2013 by Eric House (xwords@eehouse.org).  All rights reserved.
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

#include <unistd.h>
#include "udpack.h"
#include "mlock.h" 
#include "configs.h"

UDPAckTrack* UDPAckTrack::s_self = NULL;

/* static*/ bool
UDPAckTrack::shouldAck( XWRelayReg cmd )
{
    return ( XWPDEV_ACK != cmd && XWPDEV_UNAVAIL != cmd );
}

/* static*/ uint32_t
UDPAckTrack::nextPacketID( XWRelayReg cmd )
{
    uint32_t result = 0;
    if ( shouldAck( cmd ) ) {
        result = get()->nextPacketIDImpl();
        assert( PACKETID_NONE != result );
    }
    return result;
}

/* static*/ void
UDPAckTrack::recordAck( uint32_t packetID )
{
    get()->recordAckImpl( packetID );
}

/* static */ bool
UDPAckTrack::setOnAck( OnAckProc proc, uint32_t packetID, void* data )
{
    return get()->setOnAckImpl( proc, packetID, data );
}

/* static */ void
UDPAckTrack::printAcks( StrWPF& out )
{
    get()->printAcksImpl( out );
}

/* static */ void
UDPAckTrack::doNack( vector<uint32_t> ids )
{
    get()->doNackImpl( ids );
}

/* static */ UDPAckTrack*
UDPAckTrack::get()
{
    if ( NULL == s_self ) {
        s_self = new UDPAckTrack();
    }
    return s_self;
}

UDPAckTrack::UDPAckTrack()
{
    m_nextID = PACKETID_NONE;

    pthread_mutex_init( &m_mutex, NULL );

    pthread_t thread;
    pthread_create( &thread, NULL, thread_main, (void*)this );
    pthread_detach( thread );
}

time_t
UDPAckTrack::ackLimit()
{
    time_t limit;
    if ( !RelayConfigs::GetConfigs()->GetValueFor( "UDP_ACK_LIMIT", 
                                                   &limit ) ) {
        assert(0);
    }
    return limit;
}

uint32_t
UDPAckTrack::nextPacketIDImpl()
{
    MutexLock ml( &m_mutex );
    uint32_t result = ++m_nextID;
    AckRecord record;
    m_pendings.insert( pair<uint32_t,AckRecord>(result, record) );
    return result;
}

void
UDPAckTrack::recordAckImpl( uint32_t packetID )
{
    map<uint32_t, AckRecord>::iterator iter;
    MutexLock ml( &m_mutex );
    iter = m_pendings.find( packetID );
    if ( m_pendings.end() == iter ) {
        logf( XW_LOGERROR, "%s: packet ID %d not found", __func__, packetID );
    } else {
        time_t took = time( NULL ) - iter->second.m_createTime;
        if ( 5 < took  ) {
            logf( XW_LOGERROR, "%s: packet ID %d took %d seconds to get acked",
                  __func__, packetID, took );
        }

        callProc( iter, true );
        m_pendings.erase( iter );
    }
}

bool
UDPAckTrack::setOnAckImpl( OnAckProc proc, uint32_t packetID, void* data )
{
    bool canAdd = PACKETID_NONE != packetID;
    if ( canAdd ) {
        MutexLock ml( &m_mutex );
        map<uint32_t, AckRecord>::iterator iter = m_pendings.find( packetID );
        if ( m_pendings.end() != iter ) {
            iter->second.proc = proc;
            iter->second.data = data;
        }
    }
    return canAdd;
}

void
UDPAckTrack::printAcksImpl( StrWPF& out )
{
    time_t now = time( NULL );
    time_t limit = ackLimit();
    MutexLock ml( &m_mutex );
    map<uint32_t, AckRecord>::const_iterator iter;
    for ( iter = m_pendings.begin(); m_pendings.end() != iter; ++iter ) {
        out.catf( "id: % 8d; stl: %04d\n", iter->first, 
                  (iter->second.m_createTime + limit) - now );
    }
}

void
UDPAckTrack::doNackImpl( vector<uint32_t>& ids )
{
    MutexLock ml( &m_mutex );
    map<uint32_t, AckRecord>::iterator iter;
    if ( 0 == ids.size() ) {
        for ( iter = m_pendings.begin(); m_pendings.end() != iter; ) {
            callProc( iter, false );
            m_pendings.erase( iter++ );
        }
    } else {
        vector<uint32_t>::const_iterator idsIter;
        for ( idsIter = ids.begin(); ids.end() != idsIter; ++idsIter ) {
            iter = m_pendings.find( *idsIter );
            if ( m_pendings.end() != iter ) {
                callProc( iter, false );
                m_pendings.erase( iter );
            }
        }
    }
}

void
UDPAckTrack::callProc( const map<uint32_t, AckRecord>::iterator iter, bool acked )
{
    const AckRecord* record = &(iter->second);
    OnAckProc proc = record->proc;
    if ( NULL != proc ) {
        uint32_t packetID = iter->first;
        logf( XW_LOGINFO, "%s(packetID=%d, acked=%d, proc=%p)", __func__, 
              packetID, acked, proc );
        (*proc)( acked, packetID, record->data );
    }
}

void*
UDPAckTrack::threadProc()
{
    for ( ; ; ) {
        time_t limit = ackLimit();
        sleep( limit / 2 );
        vector<uint32_t> older;
        {
            MutexLock ml( &m_mutex );
            time_t now = time( NULL );
            map<uint32_t, AckRecord>::iterator iter;
            for ( iter = m_pendings.begin(); m_pendings.end() != iter; ) {
                time_t took = now - iter->second.m_createTime;
                if ( limit < took ) {
                    older.push_back( iter->first );
                    callProc( iter, false );
                    m_pendings.erase( iter++ );
                } else {
                    ++iter;
                }
            }
        }
        if ( 0 < older.size() ) {
            StrWPF leaked;
            vector<uint32_t>::const_iterator iter = older.begin();
            for ( ; ; ) {
                leaked.catf( "%d", *iter );
                if ( ++iter == older.end() ) {
                    break;
                }
                leaked.catf( ", " );
            }
            logf( XW_LOGERROR, "%s: these packets leaked (were not ack'd "
                  "within %d seconds): %s", __func__, 
                  limit, leaked.c_str() );
        }
    }
    return NULL;
}

/* static */ void*
UDPAckTrack::thread_main( void* arg )
{
    UDPAckTrack* self = (UDPAckTrack*)arg;
    return self->threadProc();
}
