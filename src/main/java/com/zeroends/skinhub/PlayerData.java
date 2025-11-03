package com.zeroends.skinhub;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PlayerData {

    // Daftar skin yang disimpan oleh pemain
    private final List<SkinInfo> skinSlots;

    public PlayerData() {
        this.skinSlots = new ArrayList<>();
    }

    /**
     * Menambahkan skin baru ke koleksi pemain.
     * @param skinInfo Info skin yang akan ditambahkan.
     * @param maxSkins Batas maksimum skin.
     * @return true jika berhasil ditambahkan, false jika slot penuh atau skin sudah ada.
     */
    public boolean addSkin(SkinInfo skinInfo, int maxSkins) {
        if (skinSlots.size() >= maxSkins) {
            return false;
        }
        // Hindari duplikasi berdasarkan texture
        for (SkinInfo existing : skinSlots) {
            if (existing.texture().equals(skinInfo.texture())) {
                return false; // Skin sudah ada
            }
        }
        skinSlots.add(skinInfo);
        return true;
    }

    /**
     * Menghapus skin dari koleksi berdasarkan ID (timestamp).
     * @param skinId ID unik (timestamp) dari skin yang akan dihapus.
     * @return true jika berhasil dihapus, false jika tidak ditemukan.
     */
    public boolean removeSkin(long skinId) {
        return skinSlots.removeIf(skin -> skin.id() == skinId);
    }

    /**
     * Mendapatkan daftar skin yang dimiliki pemain.
     * @return Daftar SkinInfo.
     */
    public List<SkinInfo> getSkinSlots() {
        return skinSlots;
    }

    /**
     * Mendapatkan skin berdasarkan ID (timestamp).
     * @param skinId ID unik skin.
     * @return SkinInfo jika ditemukan, null jika tidak.
     */
    public SkinInfo getSkinById(long skinId) {
        for (SkinInfo skin : skinSlots) {
            if (skin.id() == skinId) {
                return skin;
            }
        }
        return null;
    }

    /**
     * Record untuk menyimpan data skin individu.
     * Menggunakan System.currentTimeMillis() sebagai ID unik sederhana.
     */
    public record SkinInfo(
            String name,
            long id,
            String texture,
            String signature
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SkinInfo skinInfo = (SkinInfo) o;
            return id == skinInfo.id &&
                   texture.equals(skinInfo.texture);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, texture);
        }
    }
}
