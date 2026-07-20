## DELETE http://localhost:40055/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 20 Jul 2026 17:42:22 GMT
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
curl 'http://localhost:40055/echo' \
  -X DELETE
```
