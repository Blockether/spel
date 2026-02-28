## DELETE http://localhost:35507/echo → 200 OK

### Request Headers
```
DELETE http://localhost:35507/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 28 Feb 2026 09:04:08 GMT
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
curl 'http://localhost:35507/echo' \
  -X DELETE
```
