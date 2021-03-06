package io.repseq.gen.dist;

import com.fasterxml.jackson.annotation.*;
import io.repseq.core.GeneFeature;
import io.repseq.core.GeneType;
import io.repseq.core.VDJCGene;
import org.apache.commons.math3.random.RandomGenerator;

import static com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY;

public final class CommonNormalGeneTrimmingModel implements GeneTrimmingModel {
    @JsonUnwrapped
    @JsonProperty(access = READ_ONLY)
    public final NormalDistributionParameters parameters;
    public final int maxPLength;

    @JsonCreator
    public CommonNormalGeneTrimmingModel(@JsonProperty("mu") double mu,
                                         @JsonProperty("sigma") double sigma,
                                         @JsonProperty("maxPLength") int maxPLength) {
        this.parameters = new NormalDistributionParameters(mu, sigma);
        this.maxPLength = maxPLength;
    }

    public static GeneFeature cdr3Part(GeneType geneType) {
        switch (geneType) {
            case Joining:
                return GeneFeature.GermlineJCDR3Part;
            case Variable:
                return GeneFeature.GermlineVCDR3Part;
            default:
                throw new IllegalArgumentException();
        }
    }

    @Override
    public GeneTrimmingGenerator create(RandomGenerator random, VDJCGene gene) {
        int cdr3PartLength = gene.getPartitioning().getLength(cdr3Part(gene.getGeneType()));
        return new GeneTrimmingGeneratorEnumerated(parameters.truncatedDistribution(random, -cdr3PartLength, maxPLength));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CommonNormalGeneTrimmingModel that = (CommonNormalGeneTrimmingModel) o;

        if (maxPLength != that.maxPLength) return false;
        return parameters.equals(that.parameters);
    }

    @Override
    public int hashCode() {
        int result = parameters.hashCode();
        result = 31 * result + maxPLength;
        return result;
    }
}
