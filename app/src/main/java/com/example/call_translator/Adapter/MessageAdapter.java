package com.example.call_translator.Adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.example.call_translator.R;
import com.example.call_translator.model.Message;

import java.util.ArrayList;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>{

    ArrayList<Message> messages;

    int VIEW_MY = 1;
    int VIEW_FRIEND = 2;

    public MessageAdapter(ArrayList<Message> messages){
        this.messages = messages;
    }

    @Override
    public int getItemViewType(int position){
        if(messages.get(position).isMine){
            return VIEW_MY;
        }else{
            return VIEW_FRIEND;
        }
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType){

        if(viewType == VIEW_MY){

            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_my_message,parent,false);

            return new MyViewHolder(view);

        }else{

            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_friend_message,parent,false);

            return new FriendViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position){

        Message msg = messages.get(position);

        if(holder instanceof MyViewHolder){
            ((MyViewHolder)holder).tvMessage.setText(msg.text);
        }else{
            ((FriendViewHolder)holder).tvMessage.setText(msg.text);
        }
    }

    @Override
    public int getItemCount(){
        return messages.size();
    }

    public static class MyViewHolder extends RecyclerView.ViewHolder{

        TextView tvMessage;

        public MyViewHolder(View itemView){
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvMessage);
        }
    }

    public static class FriendViewHolder extends RecyclerView.ViewHolder{

        TextView tvMessage;

        public FriendViewHolder(View itemView){
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvMessage);
        }
    }
}
