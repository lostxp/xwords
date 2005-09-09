/* -*-mode: C; fill-column: 78; c-basic-offset: 4; compile-command: "make -k";-*- */ 
/* 
 * Copyright 1997-2000 by Eric House (fixin@peak.org).  All rights reserved.
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
#ifndef _LINUXMAIN_H_
#define _LINUXMAIN_H_

#include "main.h"
#include "dictnry.h"
#include "mempool.h"
#include "comms.h"
#include "memstream.h"
/* #include "compipe.h" */

extern int errno;

typedef struct LinuxBMStruct {
    XP_U8 nCols;
    XP_U8 nRows;
    XP_U8 nBytes;
} LinuxBMStruct;

DictionaryCtxt* linux_dictionary_make( MPFORMAL char* dictFileName );

int initListenerSocket( int port );
XP_S16 linux_tcp_send( XP_U8* buf, XP_U16 buflen, const CommsAddrRec* addrRec, 
                       void* closure );
int linux_init_socket( CommonGlobals* cGlobals );
int linux_receive( CommonGlobals* cGlobals, unsigned char* buf, int bufSize );

void linuxFireTimer( CommonGlobals* cGlobals, XWTimerReason why );


XWStreamCtxt* stream_from_msgbuf( CommonGlobals* cGlobals, char* bufPtr, 
                                  XP_U16 nBytes );
XP_UCHAR* linux_getErrString( UtilErrID id );
XP_UCHAR* strFromStream( XWStreamCtxt* stream );

void catGameHistory( CommonGlobals* cGlobals );
void catOnClose( XWStreamCtxt* stream, void* closure );

#endif
