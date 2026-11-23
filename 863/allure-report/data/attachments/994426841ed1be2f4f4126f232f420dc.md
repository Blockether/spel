## DELETE http://localhost:44161/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 23 Nov 2026 10:51:55 GMT
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
curl 'http://localhost:44161/echo' \
  -X DELETE
```
