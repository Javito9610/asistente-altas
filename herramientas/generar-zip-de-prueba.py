# Genera un bloque documental de PRUEBA con documentos que de verdad se pueden leer.
# Todo inventado: cliente ficticio, NIF de pruebas 00000000T, entidades reales pero datos falsos.
from fpdf import FPDF
from PIL import Image, ImageDraw
import zipfile, io, os

AVISO = "DOCUMENTO FICTICIO DE PRUEBA - NO USAR - DATOS INVENTADOS"

def pdf(lineas, titulo):
    d = FPDF(); d.set_margins(15, 15, 15); d.add_page(); d.set_auto_page_break(True, 15)
    d.set_font("helvetica", "B", 9); d.set_text_color(200, 60, 60)
    d.cell(0, 6, AVISO, new_x="LMARGIN", new_y="NEXT", align="C")
    d.set_text_color(0, 0, 0); d.ln(4)
    d.set_font("helvetica", "B", 14)
    d.cell(0, 9, titulo, new_x="LMARGIN", new_y="NEXT"); d.ln(2)
    d.set_font("helvetica", "", 11)
    for l in lineas:
        if l.startswith("##"):
            d.ln(2); d.set_font("helvetica", "B", 11)
            d.cell(0, 7, l[2:].strip(), new_x="LMARGIN", new_y="NEXT")
            d.set_font("helvetica", "", 11)
        else:
            d.multi_cell(w=180, h=6, text=l, new_x="LMARGIN", new_y="NEXT")
    return bytes(d.output())

dni = pdf([
    "## DATOS DEL TITULAR",
    "Apellidos: GARCIA PRUEBA",
    "Nombre: ANGELA",
    "Sexo: F        Nacionalidad: ESP",
    "Fecha de nacimiento: 14 03 1985",
    "DNI / NIF: 00000000T",
    "Validez: 14 03 2032",
    "## DOMICILIO",
    "Calle Inventada 15, 3 B",
    "15001 A Coruna (A Coruna)",
], "DOCUMENTO NACIONAL DE IDENTIDAD (simulado)")

contrato = pdf([
    "## PARTES",
    "Prestamista: KVIKU FINANCE SPAIN, S.L.U.",
    "Prestatario: ANGELA GARCIA PRUEBA, con DNI 00000000T",
    "## CONDICIONES DEL CREDITO",
    "Numero de contrato: KV-2024-0098765",
    "Fecha de firma: 12 de junio de 2024",
    "Tipo de producto: linea de credito revolving",
    "Limite de credito concedido: 1.500,00 EUR",
    "Importe dispuesto inicialmente: 900,00 EUR",
    "TIN: 20,50 %",
    "TAE: 3.218,00 %",
    "Cuota mensual minima: 75,00 EUR",
    "## OBSERVACIONES",
    "Contrato suscrito a distancia. Documento de prueba sin validez.",
], "CONTRATO DE CREDITO AL CONSUMO")

hoja = pdf([
    "## CLIENTE",
    "Dona ANGELA GARCIA PRUEBA, mayor de edad, con DNI 00000000T,",
    "con domicilio en Calle Inventada 15, 3 B, 15001 A Coruna.",
    "Telefono: 600 11 22 33",
    "Correo electronico: angela.garcia.prueba@ejemplo-invalido.test",
    "## OBJETO DEL ENCARGO",
    "Reclamacion por usura (interes notoriamente superior al normal del dinero)",
    "frente a KVIKU FINANCE SPAIN, S.L.U., contrato KV-2024-0098765.",
    "Cuantia reclamada estimada: 1.150,00 EUR",
    "## HONORARIOS",
    "Segun hoja de encargo tipo del despacho. Documento de prueba.",
    "## FIRMA",
    "En A Coruna, a 20 de septiembre de 2026.",
    "Firmado: ANGELA GARCIA PRUEBA (firmado electronicamente)",
    "Firmado: AMAGNA LEGAL, S.L.P.",
], "HOJA DE ENCARGO PROFESIONAL")

# Un justificante como FOTO: no tiene texto dentro, hay que mirarlo.
img = Image.new("RGB", (1000, 620), "white")
g = ImageDraw.Draw(img)
g.rectangle([0, 0, 999, 56], fill=(200, 60, 60))
g.text((16, 22), AVISO, fill="white")
g.text((30, 100), "KVIKU FINANCE SPAIN, S.L.U.", fill="black")
g.text((30, 140), "JUSTIFICANTE DE PAGO", fill="black")
g.line([30, 170, 970, 170], fill="black", width=2)
for i, (k, v) in enumerate([
        ("Titular", "ANGELA GARCIA PRUEBA"),
        ("DNI", "00000000T"),
        ("Contrato", "KV-2024-0098765"),
        ("Fecha", "05/11/2026"),
        ("Importe abonado", "75,00 EUR"),
        ("Concepto", "Cuota mensual linea de credito"),
        ("Total pagado hasta la fecha", "2.650,00 EUR")]):
    g.text((40, 210 + i * 48), k + ":", fill=(90, 90, 90))
    g.text((330, 210 + i * 48), v, fill="black")
buf = io.BytesIO(); img.save(buf, "JPEG", quality=88)
justificante = buf.getvalue()

notas = ("Notas del asesor (documento de prueba, datos inventados)\r\n"
         "Cliente: Angela Garcia Prueba\r\n"
         "Telefono: 600 11 22 33\r\n"
         "Correo: angela.garcia.prueba@ejemplo-invalido.test\r\n"
         "Entidad: Kviku. Tarjeta revolving contratada en junio de 2024.\r\n"
         "Dice que lleva pagados unos 2.650 EUR y que sigue debiendo casi todo.\r\n"
         "Documentacion completa y revisada. Nada pendiente.\r\n").encode("utf-8")

# El expediente va como ZIP dentro, igual que en el bloque real.
inner = io.BytesIO()
with zipfile.ZipFile(inner, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("hoja.pdf", hoja)

DESTINO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "pruebas", "zips", "garcia-prueba.zip")
os.makedirs(os.path.dirname(DESTINO), exist_ok=True)
with zipfile.ZipFile(DESTINO, "w", zipfile.ZIP_DEFLATED) as z:
    base = "García Prueba/"
    z.writestr(base + "notas.txt", notas)
    z.writestr(base + "justificante.jpg", justificante)
    z.writestr(base + "dni.pdf", dni)
    z.writestr(base + "USURA_150.zip", inner.getvalue())
    z.writestr(base + "KVIKU/contrato.pdf", contrato)

print("escrito", DESTINO, os.path.getsize(DESTINO), "bytes")
with zipfile.ZipFile(DESTINO) as z:
    for i in z.infolist():
        print("  %8d  %s" % (i.file_size, i.filename))

# ---------------------------------------------------------------------------
# Y el bloque que TIENE que parar: todo lo que puede salir torcido en un alta.
# Sirve para comprobar que el asistente no se lo traga y llama a una persona.
# ---------------------------------------------------------------------------
dni_malo = pdf([
    "## DATOS DEL TITULAR",
    "Apellidos: LOPEZ PRUEBA",
    "Nombre: MANUEL",
    "Fecha de nacimiento: 02 11 1979",
    "DNI / NIF: 11111111K",          # la letra buena de 11111111 es la H: esta esta mal a proposito
    "## DOMICILIO",
    "Avenida Inventada 3",
    "15002 A Coruna (A Coruna)",
], "DOCUMENTO NACIONAL DE IDENTIDAD (simulado)")

contrato_otro = pdf([
    "## PARTES",
    "Prestamista: CETELEM, S.A.U.",   # la carpeta dice WIZINK: no cuadra
    "Prestatario: MANUEL LOPEZ PRUEBA",
    "Con la intervencion de Dona CARMEN PRUEBA SOTO como avalista.",
    "## CONDICIONES DEL CREDITO",
    "Numero de contrato: CT-2021-0001234",
    "Fecha de firma: 4 de marzo de 2021",
    "Limite de credito concedido: 3.000,00 EUR",
    "TAE: 26,80 %",
], "CONTRATO DE CREDITO AL CONSUMO")

hoja_sin_firmar = pdf([
    "## CLIENTE",
    "Don MANUEL LOPEZ PRUEBA, con DNI 11111111K.",
    "Nombre que consta en el expediente: MANUEL LOPEZ PRIETO.",  # no coincide con el DNI
    "## OBJETO DEL ENCARGO",
    "Reclamacion frente a WIZINK BANK, S.A.",
    "## FIRMA",
    "BORRADOR SIN FIRMAR - PENDIENTE DE FIRMA DEL CLIENTE",
], "HOJA DE ENCARGO PROFESIONAL")

notas_raras = ("Notas del asesor (documento de prueba, datos inventados)\r\n"
               "Cliente: Manuel Lopez. No ha dado telefono ni correo todavia.\r\n"
               "Pendiente: confirmar con el cliente de que entidad es el contrato.\r\n"
               "Pendiente: preguntar si la avalista tambien reclama.\r\n").encode("utf-8")

RARO = os.path.join(os.path.dirname(DESTINO), "raro-prueba.zip")
with zipfile.ZipFile(RARO, "w", zipfile.ZIP_DEFLATED) as z:
    base = "López Prueba/"
    z.writestr(base + "notas.txt", notas_raras)
    z.writestr(base + "dni.pdf", dni_malo)
    z.writestr(base + "hoja.pdf", hoja_sin_firmar)
    z.writestr(base + "WIZINK/contrato.pdf", contrato_otro)

print("escrito", RARO, os.path.getsize(RARO), "bytes")
with zipfile.ZipFile(RARO) as z:
    for i in z.infolist():
        print("  %8d  %s" % (i.file_size, i.filename))
