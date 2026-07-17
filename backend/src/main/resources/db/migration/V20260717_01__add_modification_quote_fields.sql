-- PRD-016: 補回保母審核與重新報價機制所需欄位
ALTER TABLE modification_requests ADD COLUMN new_total_amount INTEGER;
ALTER TABLE modification_requests ADD COLUMN previous_status VARCHAR(30);
