## DELETE http://localhost:41335/echo → 200 OK

### Request Headers
```
DELETE http://localhost:41335/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 10 Apr 2026 08:22:03 GMT
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
curl 'http://localhost:41335/echo' \
  -X DELETE
```
