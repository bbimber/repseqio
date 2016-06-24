package io.repseq.seqbase;

import com.milaboratory.core.sequence.NucleotideSequence;
import com.milaboratory.core.sequence.provider.CachedSequenceProvider;

import java.util.TreeMap;

public final class AnySequenceResolver implements OptionalSequenceResolver {
    final TreeMap<SequenceAddress, CachedSequenceProvider<NucleotideSequence>> providers = new TreeMap<>();

    @Override
    public boolean canResolve(SequenceAddress address) {
        return true;
    }

    @Override
    public synchronized CachedSequenceProvider<NucleotideSequence> resolve(SequenceAddress address) {
        CachedSequenceProvider<NucleotideSequence> provider = providers.get(address);
        if (provider == null)
            providers.put(address, provider = new CachedSequenceProvider<>(NucleotideSequence.ALPHABET, "Can't get sequence for " + address));
        return provider;
    }
}
