package snownee.jade.test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import snownee.jade.Jade;
import snownee.jade.addon.universal.ItemStorageProvider;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.JadeIds;
import snownee.jade.impl.BlockAccessorImpl;
import snownee.jade.network.ReceiveDataPacket;
import snownee.jade.network.ServerPayloadContext;

/**
 * GameTests, registered only in the dev environment when NeoForge enables GameTests ({@code runGameTestServer}).
 */
public final class JadeGameTests {
	private static final Identifier ITEM_LIST_WITH_LARGE_ITEM = Identifier.fromNamespaceAndPath(Jade.ID, "item_list_with_large_item");

	private JadeGameTests() {
	}

	public static void register(IEventBus modBus) {
		modBus.addListener(RegisterEvent.class, event -> event.register(
				Registries.TEST_FUNCTION,
				helper -> helper.register(ITEM_LIST_WITH_LARGE_ITEM, (Consumer<GameTestHelper>) JadeGameTests::itemListWithLargeItem)));
		modBus.addListener(RegisterGameTestsEvent.class, event -> {
			Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
					Identifier.fromNamespaceAndPath(Jade.ID, "default"),
					new TestEnvironmentDefinition.AllOf(List.of()));
			event.registerTest(ITEM_LIST_WITH_LARGE_ITEM, new FunctionGameTestInstance(
					ResourceKey.create(Registries.TEST_FUNCTION, ITEM_LIST_WITH_LARGE_ITEM),
					new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 100, 0, true)));
		});
	}

	/**
	 * Upstream #824: a chest holding one item with a lot of component data (here 40 KB of custom data, like a long written
	 * book) must still send its item list; before, ReceiveDataPacket dropped the whole list to stay under 16 KB.
	 */
	private static void itemListWithLargeItem(GameTestHelper helper) {
		BlockPos chestPos = new BlockPos(1, 1, 1);
		helper.setBlock(chestPos, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(chestPos, ChestBlockEntity.class);
		CompoundTag big = new CompoundTag();
		big.putString("blob", "x".repeat(40_000));
		ItemStack heavy = new ItemStack(Items.PAPER);
		heavy.set(DataComponents.CUSTOM_DATA, CustomData.of(big));
		chest.setItem(0, heavy);
		for (int i = 1; i < 27; i++) {
			chest.setItem(i, new ItemStack(Items.STONE, i));
		}

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos absPos = helper.absolutePos(chestPos);
		BlockAccessor accessor = new BlockAccessorImpl.Builder()
				.level(helper.getLevel())
				.player(player)
				.serverData(new CompoundTag())
				.hit(new BlockHitResult(Vec3.atCenterOf(absPos), Direction.UP, absPos, false))
				.blockState(helper.getLevel().getBlockState(absPos))
				.blockEntity(() -> chest)
				.build();
		ItemStorageProvider.putData(accessor);

		List<CustomPacketPayload> sent = new ArrayList<>();
		ReceiveDataPacket.send(accessor.getServerData(), new ServerPayloadContext() {
			@Override
			public void sendPacket(CustomPacketPayload payload) {
				sent.add(payload);
			}

			@Override
			public ServerPlayer player() {
				return player;
			}
		});

		helper.assertTrue(sent.size() == 1, "expected one packet, got " + sent.size());
		CompoundTag received = ((ReceiveDataPacket) sent.getFirst()).tag();
		var list = received.get(JadeIds.UNIVERSAL_ITEM_STORAGE.toString());
		helper.assertTrue(list != null, "the item list was dropped from the packet: " + received.keySet());
		var decoded = accessor.decodeFromNbt(ItemStorageProvider.STREAM_CODEC, list).orElseThrow();
		// Jade merges equal items, so the list holds one paper and one stone entry
		var items = decoded.getValue().stream().flatMap(group -> group.views.stream()).map(ItemStack::getItem).toList();
		helper.assertTrue(items.contains(Items.PAPER) && items.contains(Items.STONE), "item list is missing items: " + items);
		helper.succeed();
	}
}
