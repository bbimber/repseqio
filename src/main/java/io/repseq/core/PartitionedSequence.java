/*
 * Copyright (c) 2014-2015, Bolotin Dmitry, Chudakov Dmitry, Shugay Mikhail
 * (here and after addressed as Inventors)
 * All Rights Reserved
 *
 * Permission to use, copy, modify and distribute any part of this program for
 * educational, research and non-profit purposes, by non-profit institutions
 * only, without fee, and without a written agreement is hereby granted,
 * provided that the above copyright notice, this paragraph and the following
 * three paragraphs appear in all copies.
 *
 * Those desiring to incorporate this work into commercial products or use for
 * commercial purposes should contact the Inventors using one of the following
 * email addresses: chudakovdm@mail.ru, chudakovdm@gmail.com
 *
 * IN NO EVENT SHALL THE INVENTORS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 * SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 * ARISING OUT OF THE USE OF THIS SOFTWARE, EVEN IF THE INVENTORS HAS BEEN
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * THE SOFTWARE PROVIDED HEREIN IS ON AN "AS IS" BASIS, AND THE INVENTORS HAS
 * NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 * MODIFICATIONS. THE INVENTORS MAKES NO REPRESENTATIONS AND EXTENDS NO
 * WARRANTIES OF ANY KIND, EITHER IMPLIED OR EXPRESS, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A
 * PARTICULAR PURPOSE, OR THAT THE USE OF THE SOFTWARE WILL NOT INFRINGE ANY
 * PATENT, TRADEMARK OR OTHER RIGHTS.
 */
package io.repseq.core;

import com.milaboratory.core.Range;
import com.milaboratory.core.sequence.Seq;
import com.milaboratory.core.sequence.SeqBuilder;

/**
 * @author Dmitry Bolotin
 * @author Stanislav Poslavsky
 */
public abstract class PartitionedSequence<S extends Seq<S>> {
    protected abstract S getSequence(Range range);

    protected abstract SequencePartitioning getPartitioning();

    public S getFeature(GeneFeature feature) {
        if (!feature.isComposite()) {
            Range range = getPartitioning().getRange(feature);
            if (range == null)
                return null;
            return getSequence(range);
        }
        Range[] ranges = getPartitioning().getRanges(feature);
        if (ranges == null)
            return null;
        if (ranges.length == 1)
            return getSequence(ranges[0]);
        int size = 0;
        for (Range range : ranges)
            size += range.length();
        S seq0 = getSequence(ranges[0]);
        SeqBuilder<S> builder = seq0.getBuilder().ensureCapacity(size).append(seq0);
        for (int i = 1; i < ranges.length; ++i)
            builder.append(getSequence(ranges[i]));
        return builder.createAndDestroy();
    }
}
