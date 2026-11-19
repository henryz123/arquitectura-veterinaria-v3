package pe.arquitecturaveterinaria.inventorysales.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.arquitecturaveterinaria.inventorysales.application.StorefrontProduct;

/**
 * Read-only adapter for B05-A. It first resolves an active tenant through the
 * hardened SECURITY DEFINER function, then relies on the normal RLS context
 * for product and balance reads.
 */
@Repository
public class StorefrontJdbcRepository {

	private final JdbcTemplate jdbcTemplate;

	public StorefrontJdbcRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<ActiveTenant> findActiveTenant(String tenantSlug) {
		return jdbcTemplate.query("""
				select tenant_id, tenant_slug
				from identity_auth.lookup_public_tenant(?)
				""", (resultSet, rowNumber) -> new ActiveTenant(resultSet.getObject("tenant_id", UUID.class),
				resultSet.getString("tenant_slug")), tenantSlug).stream().findFirst();
	}

	public List<StorefrontProduct> listPublishedPhysicalProducts() {
		return jdbcTemplate.query("""
				select p.id, c.name as category_name, p.title, p.description, p.sale_price_pen, p.image_url,
				       coalesce(b.available_quantity, 0) > 0 as available
				from inventory_sales.product p
				left join inventory_sales.product_category c
				  on c.tenant_id = p.tenant_id and c.id = p.category_id
				left join inventory_sales.stock_balance b
				  on b.tenant_id = p.tenant_id and b.product_id = p.id
				where p.status = 'ACTIVO_PUBLICADO'
				  and p.inventory_managed = true
				order by p.title, p.id
				""", this::mapProduct);
	}

	private StorefrontProduct mapProduct(ResultSet resultSet, int rowNumber) throws SQLException {
		return new StorefrontProduct(resultSet.getObject("id", UUID.class), resultSet.getString("category_name"),
				resultSet.getString("title"), resultSet.getString("description"), resultSet.getBigDecimal("sale_price_pen"),
				resultSet.getString("image_url"), resultSet.getBoolean("available"));
	}

	public record ActiveTenant(UUID id, String slug) {
	}
}
