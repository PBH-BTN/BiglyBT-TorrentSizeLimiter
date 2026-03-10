package com.ghostchu.biglyplug.torrentsizelimiter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class DeleteScore {
    private double relativeRatio;
    private double sizeWeight;
    private double weightedContribution;
    private boolean assessmentStarted;
    private double score;
}
