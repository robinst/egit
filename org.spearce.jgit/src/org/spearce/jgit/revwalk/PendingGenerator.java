/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Git Development Community nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.spearce.jgit.revwalk;

import java.io.IOException;

import org.spearce.jgit.errors.IncorrectObjectTypeException;
import org.spearce.jgit.errors.MissingObjectException;
import org.spearce.jgit.errors.StopWalkException;
import org.spearce.jgit.revwalk.filter.RevFilter;

/**
 * Default (and first pass) RevCommit Generator implementation for RevWalk.
 * <p>
 * This generator starts from a set of one or more commits and process them in
 * descending (newest to oldest) commit time order. Commits automatically cause
 * their parents to be enqueued for further processing, allowing the entire
 * commit graph to be walked. A {@link RevFilter} may be used to select a subset
 * of the commits and return them to the caller.
 */
class PendingGenerator extends Generator {
	private static final int PARSED = RevWalk.PARSED;

	private static final int SEEN = RevWalk.SEEN;

	private static final int UNINTERESTING = RevWalk.UNINTERESTING;

	private final RevWalk walker;

	private final AbstractRevQueue pending;

	private final RevFilter filter;

	private final int output;

	boolean canDispose;

	PendingGenerator(final RevWalk w, final AbstractRevQueue p,
			final RevFilter f, final int out) {
		walker = w;
		pending = p;
		filter = f;
		output = out;
		canDispose = true;
	}

	@Override
	int outputType() {
		if (pending instanceof DateRevQueue)
			return output | SORT_COMMIT_TIME_DESC;
		return output;
	}

	@Override
	RevCommit next() throws MissingObjectException,
			IncorrectObjectTypeException, IOException {
		try {
			for (;;) {
				final RevCommit c = pending.next();
				if (c == null) {
					walker.curs.release();
					return null;
				}

				final boolean produce;
				if ((c.flags & UNINTERESTING) != 0)
					produce = false;
				else
					produce = filter.include(walker, c);

				for (final RevCommit p : c.parents) {
					if ((p.flags & SEEN) != 0)
						continue;
					if ((p.flags & PARSED) == 0)
						p.parse(walker);
					p.flags |= SEEN;
					pending.add(p);
				}
				walker.carryFlagsImpl(c);

				if ((c.flags & UNINTERESTING) != 0) {
					if (pending.everbodyHasFlag(UNINTERESTING))
						throw StopWalkException.INSTANCE;
					c.dispose();
					continue;
				}

				if (produce)
					return c;
				else if (canDispose)
					c.dispose();
			}
		} catch (StopWalkException swe) {
			walker.curs.release();
			pending.clear();
			return null;
		}
	}
}
