package io.repseq.core;

import java.util.Arrays;

public abstract class AbstractReferencePointsBuilder<T extends AbstractReferencePoints<T>> {
    final int[] points;
    final boolean[] pointsToCheck;

    public AbstractReferencePointsBuilder(boolean[] pointsToCheck) {
        this.points = new int[pointsToCheck.length];
        Arrays.fill(points, -1);
        this.pointsToCheck = pointsToCheck;
    }

    protected abstract T create(int[] points);

    protected abstract int indexFromBasicReferencePoint(BasicReferencePoint point);

    private void setPosition(int index, int position) {
        // Checking position value
        if (position < -1)
            throw new IllegalArgumentException("Wrong position value: " + position);

        // Backup previous value
        int oldValue = points[index];

        try {
            // Setting position
            points[index] = position;

            // Checking for validity
            ReferencePoints.checkReferencePoints(points, pointsToCheck);

        } catch (IllegalArgumentException ex) {
            // Overwrite position with old value if check failed
            // To go back to valid array state
            points[index] = oldValue;

            // Rethrow exception
            throw ex;
        }
    }

    void setPosition(BasicReferencePoint referencePoint, int position) {
        // Setting position
        setPosition(indexFromBasicReferencePoint(referencePoint), position);
    }

    public void setPosition(ReferencePoint referencePoint, int position) {
        // Checking reference point type
        if (!referencePoint.hasNoOffset())
            throw new IllegalArgumentException("Supports only reference points without offset, " + referencePoint +
                    " is not basic.");

        try {
            // Setting position
            setPosition(referencePoint.basicPoint, position);
        } catch (IllegalArgumentException ex) {
            // Rethrow exception with additional information on error context
            throw new IllegalArgumentException("While adding " + referencePoint + " error: " + ex.getMessage());
        }
    }

    public void setPositionsFrom(AbstractReferencePoints points) {
        for (int i = 0; i < points.points.length; i++)
            if (points.points[i] != -1)
                setPosition(points.basicReferencePointFromIndex(i), points.points[i]);
    }

    public T build() {
        return create(points.clone());
    }
}
