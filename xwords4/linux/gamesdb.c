/* -*-mode:  compile-command: "make MEMDEBUG=TRUE -j3"; -*- */
/* 
 * Copyright 2000-2013 by Eric House (xwords@eehouse.org).  All rights
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

#include "gamesdb.h"
#include "main.h"

sqlite3* 
openGamesDB( const char* dbName )
{
    sqlite3* pDb = NULL;
    int result = sqlite3_open( dbName, &pDb );
    XP_ASSERT( SQLITE_OK == result );

    const char* createStr = 
        "CREATE TABLE games ( "
        "game BLOB"
        ",room VARCHAR(32)"
        ")";

    result = sqlite3_exec( pDb, createStr, NULL, NULL, NULL );
    XP_LOGF( "sqlite3_exec=>%d", result );
    // XP_ASSERT( SQLITE_OK == result );

    return pDb;
}

void
closeGamesDB( sqlite3* pDb )
{
    sqlite3_close( pDb );
    XP_LOGF( "%s finished", __func__ );
}

void
writeToDB( XWStreamCtxt* stream, void* closure )
{
    int result;
    CommonGlobals* cGlobals = (CommonGlobals*)closure;
    sqlite3_int64 selRow = cGlobals->selRow;
    sqlite3* pDb = cGlobals->pDb;
    XP_U16 len = stream_getSize( stream );
    char buf[256];
    char* query;

    sqlite3_stmt* stmt = NULL;
    XP_Bool newGame = -1 == selRow;
    if ( newGame ) {         /* new row; need to insert blob first */
        query = "INSERT INTO games (game) VALUES (?)";
    } else {
        const char* fmt = "UPDATE games SET game=? where rowid=%lld";
        snprintf( buf, sizeof(buf), fmt, selRow );
        query = buf;
    }

    result = sqlite3_prepare_v2( pDb, query, -1, &stmt, NULL );        
    XP_ASSERT( SQLITE_OK == result );
    result = sqlite3_bind_zeroblob( stmt, 1 /*col 0 ??*/, len );
    XP_ASSERT( SQLITE_OK == result );
    result = sqlite3_step( stmt );
    XP_ASSERT( SQLITE_DONE == result );

    if ( newGame ) {         /* new row; need to insert blob first */
        selRow = sqlite3_last_insert_rowid( pDb );
        XP_LOGF( "%s: new rowid: %lld", __func__, selRow );
        cGlobals->selRow = selRow;
    }

    sqlite3_blob* blob;
    result = sqlite3_blob_open( pDb, "main", "games", "game",
                                selRow, 1 /*flags: writeable*/, &blob );
    XP_ASSERT( SQLITE_OK == result );
    const XP_U8* ptr = stream_getPtr( stream );
    result = sqlite3_blob_write( blob, ptr, len, 0 );
    XP_ASSERT( SQLITE_OK == result );
    result = sqlite3_blob_close( blob );
    XP_ASSERT( SQLITE_OK == result );
    if ( !!stmt ) {
        sqlite3_finalize( stmt );
    }

    if ( newGame ) {
        (*cGlobals->firstSave)( cGlobals->firstSaveClosure );
    }
}

GSList*
listGames( GTKGamesGlobals* gg )
{
    GSList* list = NULL;
    
    sqlite3_stmt *ppStmt;
    int result = sqlite3_prepare_v2( gg->pDb, 
                                     "SELECT rowid FROM games ORDER BY rowid", -1,
                                     &ppStmt, NULL );
    XP_ASSERT( SQLITE_OK == result );
    while ( NULL != ppStmt ) {
        switch( sqlite3_step( ppStmt ) ) {
        case SQLITE_ROW:        /* have data */
        {
            sqlite3_int64* data = g_malloc( sizeof( *data ) );
            *data = sqlite3_column_int64( ppStmt, 0 );
            XP_LOGF( "%s: got a row; id=%lld", __func__, *data );
            list = g_slist_append( list, data );
        }
        break;
        case SQLITE_DONE:
            sqlite3_finalize( ppStmt );
            ppStmt = NULL;
            break;
        default:
            XP_ASSERT( 0 );
            break;
        }
    }
    return list;
}

void
getGameName( GTKGamesGlobals* XP_UNUSED(gg), const sqlite3_int64* rowid, 
             XP_UCHAR* buf, XP_U16 len )
{
    snprintf( buf, len, "Game %lld", *rowid );
    LOG_RETURNF( "%s", buf );
}

XP_Bool
loadGame( XWStreamCtxt* stream, sqlite3* pDb, sqlite3_int64 rowid )
{
    char buf[256];
    snprintf( buf, sizeof(buf), "SELECT game from games WHERE rowid = %lld", rowid );

    sqlite3_stmt *ppStmt;
    int result = sqlite3_prepare_v2( pDb, buf, -1, &ppStmt, NULL );
    XP_ASSERT( SQLITE_OK == result );
    result = sqlite3_step( ppStmt );
    XP_ASSERT( SQLITE_ROW == result );
    const void* ptr = sqlite3_column_blob( ppStmt, 0 );
    int size = sqlite3_column_bytes( ppStmt, 0 );
    stream_putBytes( stream, ptr, size );
    sqlite3_finalize( ppStmt );
    return XP_TRUE;
}
