/*
 * Copyright (C) Bernhard Seybold. All rights reserved.
 *
 * This software is published under the terms of the LGPL Software License,
 * a copy of which has been included with this distribution in the LICENSE.txt
 * file.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *
 * $Id: GameListener.java,v 1.1 2002/12/08 13:27:34 BerniMan Exp $
 */

package chesspresso.game;

import chesspresso.move.Move;

/**
 * Listener for moves made on games.
 *
 * @author  Bernhard Seybold
 * @version $Revision: 1.1 $
 */
public interface GameListener
{
    public void notifyMove(Move move, short[] nags, String preMoveComment, String postMoveComment, 
    		int plyNumber, int level);
    public void notifyLineStart(int level);
    public void notifyLineEnd(int level);
}