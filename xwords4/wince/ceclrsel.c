/* -*-mode: C; fill-column: 77; c-basic-offset: 4; -*- */
/* 
 * Copyright 2004 by Eric House (fixin@peak.org).  All rights reserved.
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

#ifdef XWFEATURE_CE_EDITCOLORS

#include <Windowsx.h>
#include "stdafx.h" 
#include <commdlg.h>

#include "ceclrsel.h" 

typedef struct ColorsDlgState {

    CEAppGlobals* globals;

    COLORREF colors[NUM_EDITABLE_COLORS];
    HBRUSH brushes[NUM_EDITABLE_COLORS];
    HWND buttons[NUM_EDITABLE_COLORS];

    XP_Bool cancelled;
    XP_Bool inited;
} ColorsDlgState;

#define FIRST_BUTTON DLBLTR_BUTTON
#define LAST_BUTTON PLAYER4_BUTTON

static void
initColorData( ColorsDlgState* cState, HWND hDlg )
{
    CEAppGlobals* globals = cState->globals;
    XP_U16 i;

    XP_ASSERT( (LAST_BUTTON - FIRST_BUTTON + 1) == NUM_EDITABLE_COLORS );

    for ( i = 0; i < NUM_EDITABLE_COLORS; ++i ) {
        COLORREF ref = globals->appPrefs.colors[i];
        cState->colors[i] = ref;
        XP_LOGF( "ref[%d] = 0x%lx", i, (unsigned long)ref );
        cState->brushes[i] = CreateSolidBrush( ref );
        cState->buttons[i] = GetDlgItem( hDlg, FIRST_BUTTON + i );
    }
} /* initColorData */

static HBRUSH
brushForButton( ColorsDlgState* cState, HWND hwndButton )
{
    XP_U16 i;
    for ( i = 0; i < NUM_EDITABLE_COLORS; ++i ) {
        if ( cState->buttons[i] == hwndButton ) {
            return cState->brushes[i];
        }
    }
    return NULL;
} /* brushForButton */

static void
deleteButtonBrushes( ColorsDlgState* cState )
{
    XP_U16 i;
    for ( i = 0; i < NUM_EDITABLE_COLORS; ++i ) {
        DeleteObject( cState->brushes[i] );
    }
} /* deleteButtonBrushes */

static void
wrapChooseColor( ColorsDlgState* cState, HWND owner, XP_U16 button )
{
    CHOOSECOLOR ccs;
    BOOL hitOk;
    COLORREF arr[16];
    XP_U16 index = button-FIRST_BUTTON;

    XP_MEMSET( &ccs, 0, sizeof(ccs) );
    XP_MEMSET( &arr, 0, sizeof(arr) );

    ccs.lStructSize = sizeof(ccs);
    ccs.hwndOwner = owner;
    ccs.rgbResult = cState->colors[index];
    ccs.lpCustColors = arr;

    ccs.Flags = CC_ANYCOLOR | CC_RGBINIT | CC_FULLOPEN;

    hitOk = ChooseColor( &ccs );

    if ( hitOk ) {
        cState->colors[index] = ccs.rgbResult;
        DeleteObject( cState->brushes[index] );
        cState->brushes[index] = CreateSolidBrush( ccs.rgbResult );
    }
} /* wrapChooseColor */

LRESULT CALLBACK
ColorsDlg( HWND hDlg, UINT message, WPARAM wParam, LPARAM lParam )
{
    ColorsDlgState* cState;
    XP_U16 wid;

    if ( message == WM_INITDIALOG ) {
        SetWindowLong( hDlg, GWL_USERDATA, lParam );

        cState = (ColorsDlgState*)lParam;
        cState->cancelled = XP_TRUE;
        cState->inited = XP_FALSE;

        return TRUE;
    } else {
        cState = (ColorsDlgState*)GetWindowLong( hDlg, GWL_USERDATA );

        if ( !cState->inited ) {
            initColorData( cState, hDlg );
            cState->inited = XP_TRUE;
        }

        switch (message) {

        case WM_CTLCOLORBTN: {
            HDC hdcButton = (HDC)wParam; 
            HWND hwndButton = (HWND)lParam;
            HBRUSH brush = brushForButton( cState, hwndButton );
/*             if ( !!brush ) { */
/*                 SetSysColors( hdcButton ) */
/*             } */
            return (BOOL)brush;
        }

        case WM_COMMAND:
            wid = LOWORD(wParam);
            switch( wid ) {

            case IDOK:
                cState->cancelled = XP_FALSE;
                /* fallthrough */

            case IDCANCEL:
                deleteButtonBrushes( cState );
                EndDialog(hDlg, wid);
                return TRUE;
            default:
                /* it's one of the color buttons.  Set up with the
                   appropriate color and launch ChooseColor */
                wrapChooseColor( cState, hDlg, wid );
                return TRUE;
            }


        }
    }

    return FALSE;
} /* ColorsDlg */

XP_Bool
ceDoColorsEdit( HWND hwnd, CEAppGlobals* globals )
{
    ColorsDlgState state;
    int result;

    XP_MEMSET( &state, 0, sizeof(state) );
    state.globals = globals;

    result = DialogBoxParam( globals->hInst, (LPCTSTR)IDD_COLORSDLG, hwnd,
                             (DLGPROC)ColorsDlg, (long)&state );

    if ( !state.cancelled ) {
        XP_U16 i;
        for ( i = 0; i < NUM_EDITABLE_COLORS; ++i ) {
            globals->appPrefs.colors[i] = state.colors[i];
        }
    }
        
    return !state.cancelled;
} /* ceDoColorsEdit */

#endif
