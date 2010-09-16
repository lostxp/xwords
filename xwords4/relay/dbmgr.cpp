/* -*-mode: C; fill-column: 78; c-basic-offset: 4; -*- */

/* 
 * Copyright 2010 by Eric House (xwords@eehouse.org).  All rights reserved.
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

#include <assert.h>
#include <stdlib.h>

#include "dbmgr.h"
#include "xwrelay_priv.h"

#define DB_NAME "xwgames"
#define TABLE_NAME "games"

static DBMgr* s_instance = NULL;

/* static */ DBMgr*
DBMgr::Get() 
{
    if ( s_instance == NULL ) {
        s_instance = new DBMgr();
    }
    return s_instance;
} /* Get */

DBMgr::DBMgr()
{
    logf( XW_LOGINFO, "%s called", __func__ );
    m_pgconn = PQconnectdb( "dbname = " DB_NAME );
    logf( XW_LOGINFO, "%s:, m_pgconn: %p", __func__, m_pgconn );        

    ConnStatusType status = PQstatus( m_pgconn );
    if ( CONNECTION_OK != status ) {
        fprintf( stderr, "%s: unable to open db; does it exist?\n", __func__ );
        exit( 1 );
    }

    /* Now figure out what the largest cid currently is.  There must be a way
       to get postgres to do this for me.... */
    /* const char* query = "SELECT cid FROM games ORDER BY - cid LIMIT 1"; */
    /* PGresult* result = PQexec( m_pgconn, query ); */
    /* if ( 0 == PQntuples( result ) ) { */
    /*     m_nextCID = 1; */
    /* } else { */
    /*     char* value = PQgetvalue( result, 0, 0 ); */
    /*     m_nextCID = 1 + atoi( value ); */
    /* } */
    /* PQclear(result); */
    /* logf( XW_LOGINFO, "%s: m_nextCID=%d", __func__, m_nextCID ); */
}
 
DBMgr::~DBMgr()
{
    logf( XW_LOGINFO, "%s called", __func__ );
    PQfinish( m_pgconn );

    assert( s_instance == this );
    s_instance = NULL;
}

void
DBMgr::AddNew( const char* cookie, const char* connName, CookieID cid, 
               int langCode, int nPlayersT, bool isPublic )
{         
#if 1
    if ( !cookie ) cookie = "";
    if ( !connName ) connName = "";

    const char* fmt = "INSERT INTO " TABLE_NAME
        "(cid, cookie, connName, nTotal, nJoined, lang, ispublic, ctime) "
        "VALUES( %d, '%s', '%s', %d, %d, %d, %s, 'now' )";
    char buf[256];
    snprintf( buf, sizeof(buf), fmt, cid/*m_nextCID++*/, cookie, connName, 
              nPlayersT, 0, langCode, isPublic?"TRUE":"FALSE" );
    logf( XW_LOGINFO, "passing %s", buf );
    execSql( buf );
#else
    const char* command = "INSERT INTO games (cookie, connName, ntotal, nhere, lang) "
        "VALUES( $1, $2, $3, $4, $5 )";
    char nPlayersTBuf[4];
    char langBuf[4];

    snprintf( nPlayersHBuf, sizeof(nPlayersHBuf), "%d", nPlayersH );
    snprintf( nPlayersTBuf, sizeof(nPlayersTBuf), "%d", nPlayersT );
    snprintf( langBuf, sizeof(langBuf), "%d", langCode );

    const char * const paramValues[] = { cookie, connName, nPlayersTBuf, nPlayersHBuf, langBuf };

    PGresult* result = PQexecParams( m_pgconn, command,
                                     sizeof(paramValues)/sizeof(paramValues[0]),
                                     NULL, /*const Oid *paramTypes,*/
                                     paramValues,
                                     NULL, /*const int *paramLengths,*/
                                     NULL, /*const int *paramFormats,*/
                                     0 /*int resultFormat*/ );
    logf( XW_LOGINFO, "PQexecParams=>%d", result );
#endif
}

CookieID
DBMgr::FindGame( const char* connName, char* cookieBuf, int bufLen,
                 int* langP, int* nPlayersTP )
{
    CookieID cid = 0;

    const char* fmt = "SELECT cid, cookie, lang, nTotal from " TABLE_NAME " where connName = '%s' "
        "LIMIT 1";
    char query[256];
    snprintf( query, sizeof(query), fmt, connName );
    logf( XW_LOGINFO, "query: %s", query );

    PGresult* result = PQexec( m_pgconn, query );
    if ( 1 == PQntuples( result ) ) {
        cid = atoi( PQgetvalue( result, 0, 0 ) );
        snprintf( cookieBuf, bufLen, "%s", PQgetvalue( result, 0, 1 ) );
        *langP = atoi( PQgetvalue( result, 0, 2 ) );
        *nPlayersTP = atoi( PQgetvalue( result, 0, 3 ) );
    }
    PQclear( result );

    logf( XW_LOGINFO, "%s(%s)=>%d", __func__, connName, cid );
    return cid;
}

CookieID
DBMgr::FindOpen( const char* cookie, int lang, int nPlayersT, int nPlayersH,
                 bool wantsPublic, char* connNameBuf, int bufLen )
{
    CookieID cid = 0;

    const char* fmt = "SELECT cid, connName FROM " TABLE_NAME " "
        "WHERE cookie = '%s' "
        "AND lang = %d "
        "AND nTotal = %d "
        "AND %d <= nTotal-nJoined "
        "AND %s = ispublic "
        "LIMIT 1";
    char query[256];
    snprintf( query, sizeof(query), fmt,
              cookie, lang, nPlayersT, nPlayersH, wantsPublic?"TRUE":"FALSE" );
    logf( XW_LOGINFO, "query: %s", query );

    PGresult* result = PQexec( m_pgconn, query );
    if ( 1 == PQntuples( result ) ) {
        cid = atoi( PQgetvalue( result, 0, 0 ) );
        snprintf( connNameBuf, bufLen, "%s", PQgetvalue( result, 0, 1 ) );
        /* cid may be 0, but should use game anyway  */
    }
    PQclear( result );
    logf( XW_LOGINFO, "%s=>%d", __func__, cid );
    return cid;
} /* FindOpen */

void
DBMgr::AddPlayers( const char* connName, int nToAdd )
{
    const char* fmt = "UPDATE " TABLE_NAME " SET nJoined = nJoined+%d "
        "WHERE connName = '%s'";
    char query[256];
    snprintf( query, sizeof(query), fmt, nToAdd, connName );
    logf( XW_LOGINFO, "%s: query: %s", __func__, query );

    execSql( query );
}

void
DBMgr::AddCID( const char* const connName, CookieID cid )
{
    const char* fmt = "UPDATE " TABLE_NAME " SET cid = %d "
        "WHERE connName = '%s'";
    char query[256];
    snprintf( query, sizeof(query), fmt, cid, connName );
    logf( XW_LOGINFO, "%s: query: %s", __func__, query );

    execSql( query );
}

void
DBMgr::ClearCIDs( void )
{
    execSql( "UPDATE " TABLE_NAME " set cid = null" );
}

void
DBMgr::execSql( const char* query )
{
    PGresult* result = PQexec( m_pgconn, query );
    if ( PGRES_COMMAND_OK != PQresultStatus(result) ) {
        logf( XW_LOGERROR, "PQEXEC=>%s", PQresultErrorMessage(result) );
        assert( 0 );
    }
    PQclear( result );
    logf( XW_LOGINFO, "PQexecParams=>%d", result );
}

/*
  Schema:
  CREATE TABLE games ( 
  cid integer,
  cookie VARCHAR(32),
  connName VARCHAR(64) UNIQUE PRIMARY KEY,
  nTotal INTEGER,
  nJoined INTEGER, 
  lang INTEGER,
  ctime TIMESTAMP,
  mtime TIMESTAMP
);

  May also want
  seeds INTEGER ARRAY,
  ipAddresses INTEGER ARRAY,

        
 */