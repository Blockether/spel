## DELETE http://localhost:35807/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 27 Jul 2026 06:57:33 GMT
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
curl 'http://localhost:35807/echo' \
  -X DELETE
```
