## DELETE http://localhost:34603/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 27 Jul 2026 13:34:43 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:34603/echo' \
  -X DELETE
```
