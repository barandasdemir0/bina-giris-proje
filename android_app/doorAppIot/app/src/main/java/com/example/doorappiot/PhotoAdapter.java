package com.example.doorappiot;

// Android UI, RecyclerView ve Picasso kütüphanesi için gerekli import'lar
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.squareup.picasso.Picasso;
import java.util.ArrayList;

//reycclerviewda birazdaha gelişmem lazım
public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {
    private Context context;
    private ArrayList<String> photoUrls;

    // Kurucu metot: Bağlam ve URL listesini başlatır
    public PhotoAdapter(Context context, ArrayList<String> photoUrls) {
        this.context = context; // Uygulama bağlamını kaydet
        this.photoUrls = photoUrls; // Fotoğraf URL'lerini kaydet
    }

    // Yeni bir ViewHolder oluşturur
    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // item_photo layout'unu şişirerek bir View oluştur
        View view = LayoutInflater.from(context).inflate(R.layout.item_photo, parent, false);
        return new PhotoViewHolder(view); // Yeni ViewHolder döndür
    }

    // ViewHolder'a verileri bağlar
    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        String photoUrl = photoUrls.get(position);
        Picasso.get()
                .load(photoUrl)
                .placeholder(android.R.drawable.ic_menu_gallery) // Yüklenirken gösterilecek yer tutucu
                .error(android.R.drawable.ic_dialog_alert)
                .into(holder.imageView); // Resmi ImageView'e yerleştir
    }

    // Listede kaç öğe olduğunu döndürür
    @Override
    public int getItemCount() {
        return photoUrls.size(); // Fotoğraf URL listesinin boyutunu döndür
    }

    // ViewHolder sınıfı: Her bir fotoğraf öğesi için UI bileşenlerini tutar
    public static class PhotoViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView; // Fotoğrafı göstermek için ImageView

        // Kurucu metot: ViewHolder'ı başlatır
        public PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.photoImageView); // Layout'taki ImageView'i bul
        }
    }
}