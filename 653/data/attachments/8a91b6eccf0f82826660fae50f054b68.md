## DELETE http://localhost:33695/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 27 Jul 2026 03:16:07 GMT
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
curl 'http://localhost:33695/echo' \
  -X DELETE
```
