/* -*- compile-command: "make MEMDEBUG=TRUE -j3"; -*- */
/* 
 * Copyright 1997 - 2013 by Eric House (xwords@eehouse.org).  All rights
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

#ifndef CLIENT_ONLY		/* there's an else in the middle!!! */

#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
/* #include <prc.h> */

#include "comtypes.h"
#include "dictnryp.h"
#include "linuxmain.h"
#include "strutils.h"
#include "linuxutl.h"
#include "dictmgr.h"

typedef struct DictStart {
    XP_U32 numNodes;
    /*    XP_U32 indexStart; */
    array_edge* array;
} DictStart;

typedef struct LinuxDictionaryCtxt {
    DictionaryCtxt super;
    XP_U8* dictBase;
    size_t dictLength;
    XP_Bool useMMap;
} LinuxDictionaryCtxt;


/************************ Prototypes ***********************/
static XP_Bool initFromDictFile( LinuxDictionaryCtxt* dctx, 
                                 const LaunchParams* params,
                                 const char* fileName );
static void linux_dictionary_destroy( DictionaryCtxt* dict );
static const XP_UCHAR* linux_dict_getShortName( const DictionaryCtxt* dict );

/*****************************************************************************
 *
 ****************************************************************************/
DictionaryCtxt* 
linux_dictionary_make( MPFORMAL const LaunchParams* params,
                       const char* dictFileName, XP_Bool useMMap )
{
    LinuxDictionaryCtxt* result = NULL;
    if ( !!dictFileName ) {
        /* dmgr_get increments ref count before returning! */
        result = (LinuxDictionaryCtxt*)dmgr_get( params->dictMgr, dictFileName );
    }
    if ( !result ) {
        result = (LinuxDictionaryCtxt*)XP_CALLOC(mpool, sizeof(*result));

        dict_super_init( &result->super );
        MPASSIGN( result->super.mpool, mpool );

        result->useMMap = useMMap;

        if ( !!dictFileName ) {
            XP_Bool success = initFromDictFile( result, params, dictFileName );
            if ( success ) {
                result->super.destructor = linux_dictionary_destroy;
                result->super.func_dict_getShortName = linux_dict_getShortName;
                setBlankTile( &result->super );
            } else {
                XP_ASSERT( 0 ); /* gonna crash anyway */
                XP_FREE( mpool, result );
                result = NULL;
            }

            dmgr_put( params->dictMgr, dictFileName, &result->super );
        }
        (void)dict_ref( &result->super );
    }

    return &result->super;
} /* gtk_dictionary_make */

static XP_UCHAR*
getNullTermParam( LinuxDictionaryCtxt* XP_UNUSED_DBG(dctx), const XP_U8** ptr,
                  XP_U16* headerLen )
{
    XP_U16 len = 1 + XP_STRLEN( (XP_UCHAR*)*ptr );
    XP_UCHAR* result = XP_MALLOC( dctx->super.mpool, len );
    XP_MEMCPY( result, *ptr, len );
    *ptr += len;
    *headerLen -= len;
    return result;
}

static XP_U16
countSpecials( LinuxDictionaryCtxt* ctxt )
{
    XP_U16 result = 0;
    XP_U16 ii;

    for ( ii = 0; ii < ctxt->super.nFaces; ++ii ) {
        if ( IS_SPECIAL(ctxt->super.facePtrs[ii][0]) ) {
            ++result;
        }
    }

    return result;
} /* countSpecials */

static XP_Bitmap
skipBitmap( LinuxDictionaryCtxt* XP_UNUSED_DBG(ctxt), const XP_U8** ptrp )
{
    XP_U8 nCols, nRows, nBytes;
    LinuxBMStruct* lbs = NULL;
    const XP_U8* ptr = *ptrp;
    
    nCols = *ptr++;
    if ( nCols > 0 ) {
        nRows = *ptr++;

        nBytes = ((nRows * nCols) + 7) / 8;

        lbs = XP_MALLOC( ctxt->super.mpool, sizeof(*lbs) + nBytes );
        lbs->nRows = nRows;
        lbs->nCols = nCols;
        lbs->nBytes = nBytes;
	
        memcpy( lbs + 1, ptr, nBytes );
        ptr += nBytes;
    }

    *ptrp = ptr;
    return lbs;
} /* skipBitmap */

static void
skipBitmaps( LinuxDictionaryCtxt* ctxt, const XP_U8** ptrp )
{
    XP_U16 nSpecials;
    XP_UCHAR* text;
    XP_UCHAR** texts;
    XP_UCHAR** textEnds;
    SpecialBitmaps* bitmaps;
    Tile tile;
    const XP_U8* ptr = *ptrp;

    nSpecials = countSpecials( ctxt );

    texts = (XP_UCHAR**)XP_MALLOC( ctxt->super.mpool, 
                                   nSpecials * sizeof(*texts) );
    textEnds = (XP_UCHAR**)XP_MALLOC( ctxt->super.mpool, 
                                      nSpecials * sizeof(*textEnds) );
    bitmaps = (SpecialBitmaps*)XP_MALLOC( ctxt->super.mpool, 
                                          nSpecials * sizeof(*bitmaps) );
    XP_MEMSET( bitmaps, 0, nSpecials * sizeof(*bitmaps) );

    for ( tile = 0; tile < ctxt->super.nFaces; ++tile ) {
	
        const XP_UCHAR* facep = ctxt->super.facePtrs[(short)tile];
        if ( IS_SPECIAL(*facep) ) {
            XP_U16 asIndex = (XP_U16)*facep;
            XP_U8 txtlen;
            XP_ASSERT( *facep < nSpecials );

            /* get the string */
            txtlen = *ptr++;
            text = (XP_UCHAR*)XP_MALLOC(ctxt->super.mpool, txtlen+1);
            memcpy( text, ptr, txtlen );
            ptr += txtlen;

            text[txtlen] = '\0';
            texts[(XP_U16)*facep] = text;
            textEnds[(XP_U16)*facep] = text + txtlen + 1;
            
            /* Now replace the delimiter char with \0.  It must be one byte in
               length and of course equal to the delimiter */
            XP_ASSERT( 0 == (SYNONYM_DELIM & 0x80) );
            while ( '\0' != *text ) {
                XP_UCHAR* cp = g_utf8_offset_to_pointer( text, 1 );
                if ( 1 == (cp - text) && *text == SYNONYM_DELIM ) {
                    *text = '\0';
                }
                text = cp;
            }

            XP_DEBUGF( "skipping bitmaps for " XP_S, texts[asIndex] );

            bitmaps[asIndex].largeBM = skipBitmap( ctxt, &ptr );
            bitmaps[asIndex].smallBM = skipBitmap( ctxt, &ptr );
        }
    }
    *ptrp = ptr;

    ctxt->super.chars = texts;
    ctxt->super.charEnds = textEnds;
    ctxt->super.bitmaps = bitmaps;
} /* skipBitmaps */

void
dict_splitFaces( DictionaryCtxt* dict, const XP_U8* utf8,
                 XP_U16 nBytes, XP_U16 nFaces )
{
    XP_UCHAR* faces = XP_MALLOC( dict->mpool, nBytes + nFaces );
    const XP_UCHAR** ptrs = XP_MALLOC( dict->mpool, nFaces * sizeof(ptrs[0]));
    XP_U16 ii;
    XP_Bool isUTF8 = dict->isUTF8;
    XP_UCHAR* next = faces;
    const gchar* bytes = (const gchar*)utf8;

    for ( ii = 0; ii < nFaces; ++ii ) {
        ptrs[ii] = next;
        if ( isUTF8 ) {
            for ( ; ; ) {
                gchar* cp = g_utf8_offset_to_pointer( bytes, 1 );
                size_t len = cp - bytes;
                XP_MEMCPY( next, bytes, len );
                next += len;
                bytes += len;
                if ( SYNONYM_DELIM != bytes[0] ) {
                    break;
                }
                ++bytes;        /* skip delimiter */
                *next++ = '\0';
            }
        } else {
            XP_ASSERT( 0 == *bytes );
            ++bytes;            /* skip empty */
            *next++ = *bytes++;
        }
        XP_ASSERT( next < faces + nFaces + nBytes );
        *next++ = '\0';
    }
    XP_ASSERT( !dict->faces );
    dict->faces = faces;
    dict->facesEnd = faces + nFaces + nBytes;
    XP_ASSERT( !dict->facePtrs );
    dict->facePtrs = ptrs;
} /* dict_splitFaces */

static XP_Bool
initFromDictFile( LinuxDictionaryCtxt* dctx, const LaunchParams* params, 
                  const char* fileName )
{
    XP_Bool formatOk = XP_TRUE;
    size_t dictLength;
    XP_U32 topOffset;
    unsigned short xloc;
    XP_U16 flags;
    XP_U16 facesSize;
    XP_U16 charSize;
    XP_Bool isUTF8 = XP_FALSE;
    XP_Bool hasHeader = XP_FALSE;
    const XP_U8* ptr;
    char path[256];

    if ( !getDictPath( params, fileName, path, VSIZE(path) ) ) {
        XP_LOGF( "%s: path=%s", __func__, path );
        goto closeAndExit;
    }
    struct stat statbuf;
    if ( 0 != stat( path, &statbuf ) || 0 == statbuf.st_size ) {
        goto closeAndExit;
    }
    dctx->dictLength = statbuf.st_size;

    {
        FILE* dictF = fopen( path, "r" );
        XP_ASSERT( !!dictF );
        if ( dctx->useMMap ) {
            dctx->dictBase = mmap( NULL, dctx->dictLength, PROT_READ, 
                                   MAP_PRIVATE, fileno(dictF), 0 );
        } else {
            dctx->dictBase = XP_MALLOC( dctx->super.mpool, dctx->dictLength );
            if ( dctx->dictLength != fread( dctx->dictBase, 1, 
                                            dctx->dictLength, dictF ) ) {
                XP_ASSERT( 0 );
            }
        }
        fclose( dictF );
    }

    ptr = dctx->dictBase;

    memcpy( &flags, ptr, sizeof(flags) );
    ptr += sizeof( flags );
    flags = ntohs(flags);

    XP_DEBUGF( "flags=0X%X", flags );
    hasHeader = 0 != (DICT_HEADER_MASK & flags);
    if ( hasHeader ) {
        flags &= ~DICT_HEADER_MASK;
        XP_DEBUGF( "has header!" );
    }

    flags &= ~DICT_SYNONYMS_MASK;

    if ( flags == 0x0001 ) {
        dctx->super.nodeSize = 3;
        charSize = 1;
        dctx->super.is_4_byte = XP_FALSE;
    } else if ( flags == 0x0002 ) {
        dctx->super.nodeSize = 3;
        charSize = 2;
        dctx->super.is_4_byte = XP_FALSE;
    } else if ( flags == 0x0003 ) {
        dctx->super.nodeSize = 4;
        charSize = 2;
        dctx->super.is_4_byte = XP_TRUE;
    } else if ( flags == 0x0004 ) {
        dctx->super.nodeSize = 3;
        dctx->super.isUTF8 = XP_TRUE;
        isUTF8 = XP_TRUE;
        dctx->super.is_4_byte = XP_FALSE;
    } else if ( flags == 0x0005 ) {
        dctx->super.nodeSize = 4;
        dctx->super.isUTF8 = XP_TRUE;
        isUTF8 = XP_TRUE;
        dctx->super.is_4_byte = XP_TRUE;
    } else {
        /* case I don't know how to deal with */
        formatOk = XP_FALSE;
        XP_ASSERT(0);
    }

    if ( formatOk ) {
        XP_U8 numFaceBytes, numFaces;

        if ( hasHeader ) {
            XP_U16 headerLen;
            XP_U32 wordCount;

            memcpy( &headerLen, ptr, sizeof(headerLen) );
            ptr += sizeof(headerLen);
            headerLen = ntohs( headerLen );

            memcpy( &wordCount, ptr, sizeof(wordCount) );
            ptr += sizeof(wordCount);
            headerLen -= sizeof(wordCount);
            dctx->super.nWords = ntohl( wordCount );
            XP_DEBUGF( "dict contains %d words", dctx->super.nWords );

            if ( 0 < headerLen ) {
                dctx->super.desc = getNullTermParam( dctx, &ptr, &headerLen );
            } else {
                XP_LOGF( "%s: no note", __func__ );
            }
            if ( 0 < headerLen ) {
                dctx->super.md5Sum = getNullTermParam( dctx, &ptr, &headerLen );
            } else {
                XP_LOGF( "%s: no md5Sum", __func__ );
            }
            ptr += headerLen;
        }

        if ( isUTF8 ) {
            numFaceBytes = *ptr++;
        }
        numFaces = *ptr++;
        if ( !isUTF8 ) {
            numFaceBytes = numFaces * charSize;
        }

        if ( NULL == dctx->super.md5Sum
#ifdef DEBUG
             || XP_TRUE 
#endif
             ) {
            size_t curPos = ptr - dctx->dictBase;
            gssize dictLength = dctx->dictLength - curPos;

            gchar* checksum = g_compute_checksum_for_data( G_CHECKSUM_MD5, ptr, dictLength );
            if ( NULL == dctx->super.md5Sum ) {
                dctx->super.md5Sum = copyString( dctx->super.mpool, checksum );
            } else {
                XP_ASSERT( 0 == XP_STRCMP( dctx->super.md5Sum, checksum ) );
            }
            g_free( checksum );
        }

        dctx->super.nFaces = numFaces;

        dctx->super.countsAndValues = XP_MALLOC( dctx->super.mpool, 
                                                 numFaces*2 );
        facesSize = numFaceBytes;
        if ( !isUTF8 ) {
            facesSize /= 2;
        }

        XP_U8 tmp[numFaceBytes];
        memcpy( tmp, ptr, numFaceBytes );
        ptr += numFaceBytes;

        dict_splitFaces( &dctx->super, tmp, numFaceBytes, numFaces );

        memcpy( &xloc, ptr, sizeof(xloc) );
        ptr += sizeof(xloc);
        memcpy( dctx->super.countsAndValues, ptr, numFaces*2 );
        ptr += numFaces*2;
    }
    
    dctx->super.langCode = xloc & 0x7F;

    if ( formatOk ) {
        XP_U32 numEdges;
        skipBitmaps( dctx, &ptr );

        size_t curPos = ptr - dctx->dictBase;
        dictLength = dctx->dictLength - curPos;

        if ( dictLength > 0 ) {
            memcpy( &topOffset, ptr, sizeof(topOffset) );
            /* it's in big-endian order */
            topOffset = ntohl(topOffset);
            dictLength -= sizeof(topOffset); /* first four bytes are offset */
            ptr += sizeof(topOffset);
        }

        if ( dictLength > 0 ) {
            numEdges = dictLength / dctx->super.nodeSize;
#ifdef DEBUG
            XP_ASSERT( (dictLength % dctx->super.nodeSize) == 0 );
            dctx->super.numEdges = numEdges;
#endif
            dctx->super.base = (array_edge*)ptr;

            dctx->super.topEdge = dctx->super.base + topOffset;
        } else {
            dctx->super.base = NULL;
            dctx->super.topEdge = NULL;
            numEdges = 0;
        }

        dctx->super.name = copyString( dctx->super.mpool, fileName );

        if ( ! checkSanity( &dctx->super, numEdges ) ) {
            goto closeAndExit;
        }
    }
    goto ok;

 closeAndExit:
    formatOk = XP_FALSE;
 ok:

    return formatOk;
} /* initFromDictFile */

static void
freeSpecials( LinuxDictionaryCtxt* ctxt )
{
    XP_U16 nSpecials = 0;
    XP_U16 ii;

    for ( ii = 0; ii < ctxt->super.nFaces; ++ii ) {
        if ( IS_SPECIAL(ctxt->super.facePtrs[ii][0] ) ) {
            if ( !!ctxt->super.bitmaps ) {
                XP_Bitmap* bmp = ctxt->super.bitmaps[nSpecials].largeBM;
                if ( !!bmp ) {
                    XP_FREE( ctxt->super.mpool, bmp );
                }
                bmp = ctxt->super.bitmaps[nSpecials].smallBM;
                if ( !!bmp ) {
                    XP_FREE( ctxt->super.mpool, bmp );
                }
            }
            if ( !!ctxt->super.chars && !!ctxt->super.chars[nSpecials]) {
                XP_FREE( ctxt->super.mpool, ctxt->super.chars[nSpecials] );
            }
            ++nSpecials;
        }
    }
    if ( !!ctxt->super.bitmaps ) {
        XP_FREE( ctxt->super.mpool, ctxt->super.bitmaps );
    }
    XP_FREEP( ctxt->super.mpool, &ctxt->super.chars );
    XP_FREEP( ctxt->super.mpool, &ctxt->super.charEnds );
} /* freeSpecials */

static void
linux_dictionary_destroy( DictionaryCtxt* dict )
{
    LinuxDictionaryCtxt* ctxt = (LinuxDictionaryCtxt*)dict;

    freeSpecials( ctxt );

    if ( !!ctxt->dictBase ) {
        if ( ctxt->useMMap ) {
            (void)munmap( ctxt->dictBase, ctxt->dictLength );
        } else {
            XP_FREE( dict->mpool, ctxt->dictBase );
        }
    }

    XP_FREEP( dict->mpool, &ctxt->super.desc );
    XP_FREEP( dict->mpool, &ctxt->super.md5Sum );
    XP_FREE( dict->mpool, ctxt->super.countsAndValues );
    XP_FREE( dict->mpool, ctxt->super.faces );
    XP_FREE( dict->mpool, ctxt->super.facePtrs );
    XP_FREE( dict->mpool, ctxt->super.name );
    XP_FREE( dict->mpool, ctxt );
} /* linux_dictionary_destroy */

static const XP_UCHAR*
linux_dict_getShortName( const DictionaryCtxt* dict )
{
    const XP_UCHAR* full = dict_getName( dict );
    const XP_UCHAR* c = strchr( full, '/' );
    if ( !!c ) {
        ++c;
    } else {
        c = full;
    }
    return c;
}

#else  /* CLIENT_ONLY *IS* defined */

/* initFromDictFile:
 * This guy reads in from a prc file, and probably hasn't worked in a year.
 */
#define RECS_BEFORE_DAWG 3	/* a hack */
static XP_Bool
initFromDictFile( LinuxDictionaryCtxt* dctx, const char* fileName )
{
    short i;
    unsigned short* dataP;
    unsigned nRecs;
    prc_record_t* prect;

    prc_t* pt = prcopen( fileName, PRC_OPEN_READ );
    dctx->pt = pt;		/* remember so we can close it later */

    nRecs = prcgetnrecords( pt );

    /* record 0 holds a struct whose 5th byte is the record num of the first
       dawg record. 1 and 2 hold tile data.  Let's assume 3 is the first dawg
       record for now. */

    prect = prcgetrecord( pt, 1 );
    dctx->super.numFaces = prect->datalen; /* one char per byte */
    dctx->super.faces = malloc( prect->datalen );
    memcpy( dctx->super.faces, prect->data, prect->datalen );
    
    dctx->super.counts = malloc( dctx->super.numFaces );
    dctx->super.values = malloc( dctx->super.numFaces );

    prect = prcgetrecord( pt, 2 );
    dataP = (unsigned short*)prect->data + 1;	/* skip the xloc header */

    for ( ii = 0; ii < dctx->super.numFaces; ++ii ) {
        unsigned short byt = *dataP++;
        dctx->super.values[ii] = byt >> 8;
        dctx->super.counts[ii] = byt & 0xFF;
        if ( dctx->super.values[ii] == 0 ) {
            dctx->super.counts[ii] = 4; /* 4 blanks :-) */
        }
    }

    dctx->numStarts = nRecs - RECS_BEFORE_DAWG;
    dctx->starts = XP_MALLOC( dctx->numStarts * sizeof(*dctx->starts) );

    for ( i = 0/* , offset = 0 */; i < dctx->numStarts; ++i ) {
        prect = prcgetrecord( pt, i + RECS_BEFORE_DAWG );
        dctx->starts[i].numNodes = prect->datalen / 3;
        dctx->starts[i].array = (array_edge*)prect->data;

        XP_ASSERT( (prect->datalen % 3) == 0 );
    }
} /* initFromDictFile */

void
linux_dictionary_destroy( DictionaryCtxt* dict )
{
    LinuxDictionaryCtxt* ctxt = (LinuxDictionaryCtxt*)dict;
    prcclose( ctxt->pt );
}

#endif /* CLIENT_ONLY */

