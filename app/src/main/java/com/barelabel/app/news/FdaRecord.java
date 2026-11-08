package com.barelabel.app.news;

import java.util.Objects;

/**
 * One openFDA food/enforcement record (Contract §2.1 fields used by
 * the FDA matcher). Plain immutable value type; string fields are
 * never null ("" when not reported) and the report date is a
 * YYYYMMDD int (0 when not reported or unparseable).
 */
public final class FdaRecord {

    public final String recallingFirm;
    public final String productDescription;
    public final String reasonForRecall;
    public final String classification;
    public final String status;
    public final int reportDateYyyymmdd;
    public final String eventId;
    public final String recallNumber;

    public FdaRecord(String recallingFirm, String productDescription,
                     String reasonForRecall, String classification,
                     String status, int reportDateYyyymmdd,
                     String eventId, String recallNumber) {
        this.recallingFirm = recallingFirm != null ? recallingFirm : "";
        this.productDescription =
                productDescription != null ? productDescription : "";
        this.reasonForRecall =
                reasonForRecall != null ? reasonForRecall : "";
        this.classification = classification != null ? classification : "";
        this.status = status != null ? status : "";
        this.reportDateYyyymmdd = reportDateYyyymmdd;
        this.eventId = eventId != null ? eventId : "";
        this.recallNumber = recallNumber != null ? recallNumber : "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FdaRecord)) {
            return false;
        }
        FdaRecord other = (FdaRecord) o;
        return reportDateYyyymmdd == other.reportDateYyyymmdd
                && Objects.equals(recallingFirm, other.recallingFirm)
                && Objects.equals(productDescription,
                        other.productDescription)
                && Objects.equals(reasonForRecall, other.reasonForRecall)
                && Objects.equals(classification, other.classification)
                && Objects.equals(status, other.status)
                && Objects.equals(eventId, other.eventId)
                && Objects.equals(recallNumber, other.recallNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recallingFirm, productDescription,
                reasonForRecall, classification, status,
                reportDateYyyymmdd, eventId, recallNumber);
    }

    @Override
    public String toString() {
        return "FdaRecord{recallingFirm=" + recallingFirm
                + ", classification=" + classification
                + ", status=" + status
                + ", reportDateYyyymmdd=" + reportDateYyyymmdd
                + ", eventId=" + eventId
                + ", recallNumber=" + recallNumber
                + ", productDescription=" + productDescription
                + ", reasonForRecall=" + reasonForRecall
                + "}";
    }
}
