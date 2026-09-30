package io.github.junkers4.ladybird.core.engine

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@Requirement("ARCH-007", "UI-005", "UI-006", "UI-007")
class EngineMessagesTest {
    private val menuJson = """
        {"title":"Page","items":[
          {"type":"separator"},
          {"type":"action","index":0,"id":3,"text":"Back","enabled":false,"visible":true,"checkable":false,"checked":false},
          {"type":"action","index":1,"id":4,"text":"Hidden","enabled":true,"visible":false,"checkable":false,"checked":false},
          {"type":"separator"},{"type":"separator"},
          {"type":"submenu","title":"Zoom","visible":true,"items":[
             {"type":"action","index":2,"id":9,"text":"Zoom In","enabled":true,"visible":true,"checkable":false,"checked":false}]},
          {"type":"submenu","title":"Empty","visible":true,"items":[]},
          {"type":"action","index":3,"id":10,"text":"Dark","enabled":true,"visible":true,"checkable":true,"checked":true},
          {"type":"separator"}
        ]}
    """.trimIndent()

    @Test
    fun parsesMenus() {
        val menu = EngineMenu.parse(menuJson)!!
        assertEquals("Page", menu.title)
        assertEquals(9, menu.items.size)
        val back = menu.items[1] as EngineMenu.Item.Action
        assertEquals(0, back.index)
        assertEquals(false, back.enabled)
        val dark = menu.items[7] as EngineMenu.Item.Action
        assertEquals(true, dark.checkable && dark.checked)
    }

    @Test
    fun tidiesVisibleItems() {
        val visible = EngineMenu.parse(menuJson)!!.visibleItems()
        assertEquals(4, visible.size)
        assertEquals("Back", (visible[0] as EngineMenu.Item.Action).text)
        assertEquals(EngineMenu.Item.Separator, visible[1])
        assertEquals("Zoom", (visible[2] as EngineMenu.Item.Submenu).title)
        assertEquals("Dark", (visible[3] as EngineMenu.Item.Action).text)
    }

    @Test
    fun rejectsMalformedMenus() {
        assertNull(EngineMenu.parse("{"))
        assertNull(EngineMenu.parse("""{"title":"x"}"""))
        assertNull(EngineMenu.parse("""{"title":"x","items":[{"type":"action"}]}"""))
        assertNull(EngineMenu.parse("""{"title":"x","items":[{"type":"bogus"}]}"""))
        assertNull(EngineMenu.parse("""{"title":"x","items":[1]}"""))
    }

    @Test
    fun parsesSelectItems() {
        val items = SelectItem.parseList(
            """[{"type":"option","id":1,"label":"One","selected":true,"disabled":false},
               {"type":"group","label":"G","items":[{"type":"option","id":2,"label":"Two","selected":false,"disabled":true}]},
               {"type":"separator"}]""",
        )!!
        assertEquals(SelectItem.Option(1, "One", selected = true, disabled = false), items[0])
        assertEquals(SelectItem.Option(2, "Two", selected = false, disabled = true), (items[1] as SelectItem.Group).options.single())
        assertEquals(SelectItem.Separator, items[2])
        val flat = SelectItem.flatten(items)
        assertEquals(listOf("One", "G", "  Two"), flat.map { it.second })
        assertNull(flat[1].first)
        assertNull(SelectItem.parseList("""[{"type":"option","label":"no id"}]"""))
        assertNull(SelectItem.parseList("""{"not":"a list"}"""))
    }

    @Test
    fun parsesEngineEvents() {
        assertEquals(OpenUrlRequest("https://a.b/", activate = false, private = true), OpenUrlRequest.parse("""{"url":"https://a.b/","activate":false,"private":true}"""))
        assertNull(OpenUrlRequest.parse("""{"activate":true}"""))
        assertEquals(FinishedDownload("a.pdf", "/data/x/a.pdf"), FinishedDownload.parse("""{"name":"a.pdf","path":"/data/x/a.pdf"}"""))
        assertNull(FinishedDownload.parse("[]"))
        val update = ApplicationMenuUpdate.parse("""{"name":"debug","menu":"{\"title\":\"Debug\",\"items\":[]}"}""")!!
        assertEquals("debug", update.name)
        assertEquals("Debug", update.menu.title)
        assertEquals("Zoom", ApplicationMenuUpdate.parse("""{"name":"zoom","menu":{"title":"Zoom","items":[]}}""")!!.menu.title)
        assertNull(ApplicationMenuUpdate.parse("""{"name":"zoom"}"""))
    }
}
