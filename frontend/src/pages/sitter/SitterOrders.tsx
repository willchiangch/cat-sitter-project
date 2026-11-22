import OrderListPage from '../shared/OrderListPage';

/** 保母訂單列表（SD-ORDER §9.1 `/sitter/orders`，E4）；核款與行程入口移到訂單詳情 `/sitter/orders/:orderId` */
const SitterOrders = () => <OrderListPage role="sitter" />;

export default SitterOrders;
