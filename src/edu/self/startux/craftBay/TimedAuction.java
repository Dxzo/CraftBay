/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright 2012 StarTux
 *
 * This file is part of CraftBay.
 *
 * CraftBay is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * CraftBay is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with CraftBay.  If not, see <http://www.gnu.org/licenses/>.
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

package edu.self.startux.craftBay;

import edu.self.startux.craftBay.event.AuctionTickEvent;
import edu.self.startux.craftBay.event.AuctionBidEvent;
import edu.self.startux.craftBay.event.AuctionStartEvent;
import edu.self.startux.craftBay.locale.Message;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TimedAuction extends AbstractAuction {
	private int timeLeft;
        private int minbid;
        private int minIncrement = 5;
        private LinkedList<Bid> bids = new LinkedList<Bid>();

	public TimedAuction(CraftBayPlugin plugin, Merchant owner, Item item) {
                super(plugin, owner, item);
                minbid = plugin.getConfig().getInt("startingbid");
                timeLeft = plugin.getConfig().getInt("auctiontime");
                minIncrement = plugin.getConfig().getInt("minincrement");
	}

        @Override
        public void start() {
                scheduleTick(true);
                setState(AuctionState.RUNNING);
                AuctionStartEvent event = new AuctionStartEvent(this);
                getPlugin().getServer().getPluginManager().callEvent(event);
        }

        @Override
        public int getTimeLeft() {
                return timeLeft;
        }

        @Override
        public void setTimeLeft(int time) {
                timeLeft = time;
        }

        @Override
        public int getStartingBid() {
                return minbid;
        }

        @Override
        public void setStartingBid(int amount) {
                minbid = amount;
        }

	@Override
	public void tick() {
                if (timeLeft <= 0) {
			end();
                        return;
                }
                getPlugin().getServer().getPluginManager().callEvent(new AuctionTickEvent(this));
                timeLeft -= 1;
        }

        @Override
        public void end() {
                setState(AuctionState.ENDED);
                stop();
                getPlugin().getAuctionScheduler().soon();
        }

        @Override
	public void stop() {
                scheduleTick(false);
        }

        @Override
        public void cancel() {
                if (getState() != AuctionState.RUNNING && getState() != AuctionState.QUEUED) return;
                setState(AuctionState.CANCELED);
                stop();
                getPlugin().getAuctionScheduler().soon();
	}

        @Override
        public Merchant getWinner() {
                if (bids.isEmpty()) {
                        return null;
                }
                return bids.getFirst().getBidder();
        }

        private int getBid(Merchant merchant) {
                for (Bid bid : bids) {
                        if (merchant.equals(bid.getBidder())) {
                                return bid.getAmount();
                        }
                }
                return 0;
        }

        /**
         * Get the highest bid so far.
         */
        private int getMaxBid() {
                if (bids.isEmpty()) {
                        return minbid;
                }
                return bids.getFirst().getAmount();
        }

        /**
         * Get the amount that the current winner would have to
         * pay, should he win.
         */
        @Override
        public int getWinningBid() {
                if (bids.isEmpty()) return 0;
                Merchant winner = getWinner();
                if (bids.size() == 1) return minbid;
                for (Bid bid : bids) {
                        if (!bid.getBidder().equals(winner)) {
                                return Math.min(bid.getAmount() + minIncrement, getMaxBid());
                        }
                }
                return minbid;
        }

        /**
         * Get the minimum amount that a new bidder has to make to
         * be accepted.
         */
        @Override
        public int getMinimalBid() {
                if (bids.isEmpty()) return minbid;
                return getWinningBid() + minIncrement;
        }

        /**
         * Insert a new bid and assert descending order of bids by
         * amount. Assume validity of bid.
         */
        private void addBid(Bid bid) {
                if (bids.isEmpty()) {
                        bids.add(bid);
                        return;
                }
                for (ListIterator<Bid> iter = bids.listIterator(); iter.hasNext();) {
                        Bid other = iter.next();
                        if (bid.getAmount() > other.getAmount()) {
                                iter.previous();
                                iter.add(bid);
                                return;
                        }
                }
                bids.addLast(bid);
        }
        
        @Override
	public boolean bid(Merchant bidder, int bid) {
                if (getState() != AuctionState.RUNNING) return false;
		if (bidder.equals(getOwner()) && !bidder.equals(BankMerchant.getInstance())) {
			bidder.warn(getPlugin().getMessage("auction.bid.IsOwner").set(this, bidder));
			return false;
		}
		if (bidder.equals(getWinner()) && bid <= getMaxBid() && bidder != BankMerchant.getInstance()) {
			bidder.warn(getPlugin().getMessage("auction.bid.UnderbidSelf").set(this, bidder));
		 	return false;
		}
                if (bid < getMinimalBid()) {
			bidder.warn(getPlugin().getMessage("auction.bid.BidTooSmall").set(this, bidder));
                        return false;
                }
                if (!bidder.hasAmount(bid)) {
			bidder.warn(getPlugin().getMessage("auction.bid.TooPoor").set(this, bidder));
                        return false;
                }
                int oldPrice = getWinningBid();
                Merchant oldWinner = getWinner();
                addBid(new Bid(bidder, bid));
                AuctionBidEvent event = new AuctionBidEvent(this, bidder, bid, oldWinner, oldPrice);
                getPlugin().getServer().getPluginManager().callEvent(event);
                return true;
        }

        @Override
        public Map<String, Object> serialize() {
                Map<String, Object> result = new HashMap<String, Object>();
                result.put("owner", getOwner().clone());
                result.put("minbid", minbid);
                result.put("timeleft", timeLeft);
                result.put("state", getState().name());
                result.put("item", getItem().clone());
                result.put("bids", bids);
                result.put("fee", getFee());
                return result;
        }

        @SuppressWarnings("unchecked")
        public static TimedAuction deserialize(Map<String, Object> map) {
                CraftBayPlugin plugin = CraftBayPlugin.getInstance();
                Merchant owner = (Merchant)map.get("owner");
                Item item = (Item)map.get("item");
                TimedAuction result = new TimedAuction(plugin, owner, item);
                result.timeLeft = (Integer)map.get("timeleft");
                result.minbid = (Integer)map.get("minbid");
                result.setState(AuctionState.getByName((String)map.get("state")));
                result.bids = new LinkedList<Bid>((List<Bid>)map.get("bids"));
                result.setFee((Integer)map.get("fee"));
                return result;
        }
}