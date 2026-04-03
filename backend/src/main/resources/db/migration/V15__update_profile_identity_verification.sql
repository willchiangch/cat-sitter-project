-- V15: 替換保母身分驗證欄位，使用人臉照片取代身分證反面

ALTER TABLE profiles
ADD COLUMN face_photo_url VARCHAR(1024);

ALTER TABLE profiles
DROP COLUMN id_card_back_url;
